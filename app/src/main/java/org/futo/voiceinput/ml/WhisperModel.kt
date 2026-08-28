package org.futo.voiceinput.ml

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.futo.voiceinput.ModelData
import org.futo.voiceinput.ggml.BailLanguageException
import org.futo.voiceinput.ggml.DecodingMode
import org.futo.voiceinput.ggml.WhisperGGML
import org.futo.voiceinput.migration.MigrationActivity
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel


/**
 * This is necessary to synchronize so two threads don't try to use the same tensor at once,
 * free a model while it's in use, etc.
 */
@OptIn(DelicateCoroutinesApi::class)
private val inferenceContext = newSingleThreadContext("InferenceContext")


@Throws(IOException::class)
private fun Context.tryOpenDownloadedModel(pathStr: String): MappedByteBuffer {
    return File(this.filesDir, pathStr).inputStream().use { fis ->
        fis.channel.use { channel ->
            channel.map(
                FileChannel.MapMode.READ_ONLY,
                0, channel.size()
            ).load()
        }
    }
}

enum class RunState {
    ExtractingFeatures,
    ProcessingEncoder,
    StartedDecoding,
    SwitchingModel,
    OOMError
}

@Throws(IOException::class)
private fun loadMappedFile(context: Context, filePath: String): MappedByteBuffer =
    context.assets.openFd(filePath).use { fileDescriptor ->
        FileInputStream(fileDescriptor.fileDescriptor).use { inputStream ->
            inputStream.channel.map(
                FileChannel.MapMode.READ_ONLY,
                fileDescriptor.startOffset,
                fileDescriptor.declaredLength
            )
        }
    }

@Throws(IOException::class)
fun loadGGMLModel(context: Context, model: ModelData, onPartialDecode: (String) -> Unit): WhisperGGML {
    val modelBuffer = if(model.ggml.is_builtin_asset) {
        loadMappedFile(context, model.ggml.ggml_file)
    } else {
        context.tryOpenDownloadedModel(model.ggml.ggml_file)
    }

    return WhisperGGML(modelBuffer, onPartialDecode)
}

/**
 * Process-wide cache of loaded [WhisperGGML] models.
 *
 * Loading a model means memory-mapping the (up to gigabyte-sized) weights and paging them in during
 * the first inference. When recognition runs back-to-back - the IME dictating repeatedly, or an app
 * hammering the SpeechRecognizer - repeating that work each time is the dominant latency. This keeps
 * the last-used models resident between sessions and only frees them once nothing has used them for
 * [EVICTION_DELAY_MS], or on memory pressure via [evictAll].
 */
@OptIn(DelicateCoroutinesApi::class)
object WhisperModelCache {
    private const val EVICTION_DELAY_MS = 30_000L

    private val lock = Any()
    private val entries = HashMap<String, WhisperGGML>()
    private var activeLeases = 0
    private var evictionJob: Job? = null

    private fun keyOf(model: ModelData): String =
        (if (model.ggml.is_builtin_asset) "asset:" else "file:") + model.ggml.ggml_file

    /**
     * Returns a ready model for [model], reusing the cached instance when possible. Every successful
     * call must be balanced by exactly one [release]. May block to load the model and may throw the
     * same exceptions as [loadGGMLModel].
     */
    @Throws(IOException::class)
    fun acquire(context: Context, model: ModelData, onPartialDecode: (String) -> Unit): WhisperGGML {
        val key = keyOf(model)

        synchronized(lock) {
            evictionJob?.cancel()
            evictionJob = null
            activeLeases++

            val cached = entries[key]
            if (cached != null && cached.isOpen) {
                cached.partialResultCallback = onPartialDecode
                Log.i("WhisperModelCache", "hit $key (warm)")
                return cached
            }
            entries.remove(key)
        }

        Log.i("WhisperModelCache", "miss $key (loading)")

        val loaded = try {
            loadGGMLModel(context, model, onPartialDecode)
        } catch (e: Throwable) {
            release()
            throw e
        }

        synchronized(lock) {
            val raced = entries[key]
            if (raced != null && raced.isOpen) {
                raced.partialResultCallback = onPartialDecode
                GlobalScope.launch { runCatching { loaded.close() } }
                return raced
            }
            entries[key] = loaded
            return loaded
        }
    }

    /** Balances one [acquire]. When the last lease is returned, schedules idle eviction. */
    fun release() {
        synchronized(lock) {
            activeLeases = (activeLeases - 1).coerceAtLeast(0)
            if (activeLeases == 0) {
                evictionJob?.cancel()
                evictionJob = GlobalScope.launch {
                    delay(EVICTION_DELAY_MS)
                    evictAll()
                }
            }
        }
    }

    /** Frees every cached model now. Safe to call at any time (e.g. from onTrimMemory). */
    fun evictAll() {
        val toClose: List<WhisperGGML>
        synchronized(lock) {
            evictionJob?.cancel()
            evictionJob = null
            toClose = entries.values.toList()
            entries.clear()
        }
        GlobalScope.launch { toClose.forEach { runCatching { it.close() } } }
    }
}

private fun openMigrationIfModelIsLegacy(context: Context, model: ModelData) {
    if(listOf(
        model.legacy.encoder_xatn_file,
        model.legacy.decoder_file,
        model.legacy.vocab_file
    ).all { File(context.filesDir, it).exists() }) {
        // We are in the legacy model workflow, which is no longer supported
        // Immediately open the migration menu
        val intent = Intent(context, MigrationActivity::class.java)

        if(context !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        context.startActivity(intent)
    }
}

class WhisperModelWrapper(
    val context: Context,
    primaryModel: ModelData,
    fallbackEnglishModel: ModelData?,
    private val suppressNonSpeech: Boolean,
    private val languages: Set<String>,

    private val onStatusUpdate: (RunState) -> Unit,
    private val onPartialDecode: (String) -> Unit,
) {
    private var primaryModelGGML: WhisperGGML? = null
    private var fallbackModelGGML: WhisperGGML? = null

    /** Number of outstanding [WhisperModelCache.acquire] calls this wrapper is responsible for. */
    private var leasesHeld = 0

    init {
        if(primaryModel == fallbackEnglishModel) {
            throw IllegalArgumentException("Fallback model must be unique from the primary model")
        }

        try {
            primaryModelGGML = WhisperModelCache.acquire(context, primaryModel, onPartialDecode)
            leasesHeld++
        } catch(e: Exception) {
            Log.e("WhisperModel", "Exception during loading primary ggml model: ${e.stackTraceToString()}")
            openMigrationIfModelIsLegacy(context, primaryModel)
            throw e
        }

        fallbackEnglishModel?.let { fallbackEnglishModel ->
            try {
                fallbackModelGGML = WhisperModelCache.acquire(context, fallbackEnglishModel, onPartialDecode)
                leasesHeld++
            } catch(e: Exception) {
                runBlocking { releaseLeases() }

                Log.e("WhisperModel", "Exception during loading fallback ggml model: ${e.stackTraceToString()}")
                openMigrationIfModelIsLegacy(context, fallbackEnglishModel)
                throw e
            }
        }
    }

    private fun releaseLeases() {
        while (leasesHeld > 0) {
            WhisperModelCache.release()
            leasesHeld--
        }
    }

    private var modelJob: Job? = null
    suspend fun run(
        samples: FloatArray,
        glossary: String,
        forceLanguage: String?,
        decodingMode: DecodingMode
    ): String {
        yield()

        // TODO: This only works well for English, it may cause weird behavior with other languages
        // (maybe need to translate "Glossary" per language, or language-neutral way of expressing)
        val glossaryCleaned = glossary.trim().replace("\n", ", ").replace("  ", " ")
        val prompt = if(glossary.isBlank()) "" else "(Glossary: ${glossaryCleaned})"

        val languagesOrLanguage = forceLanguage?.let { arrayOf(it) } ?: languages.toTypedArray()

        val bailLanguages = if(fallbackModelGGML != null) {
            arrayOf("en")
        } else {
            arrayOf()
        }

        if(primaryModelGGML != null) {
            // TODO: Early exiting from native code if cancelled
            return try {
                yield()
                onStatusUpdate(RunState.ProcessingEncoder)
                primaryModelGGML!!.infer(
                    samples,
                    prompt,
                    languagesOrLanguage,
                    bailLanguages,
                    decodingMode,
                    suppressNonSpeech
                )
            }catch(e: BailLanguageException) {
                yield()
                onStatusUpdate(RunState.SwitchingModel)
                assert(e.language == "en")

                if(fallbackModelGGML != null) {
                    fallbackModelGGML!!.infer(samples, prompt, languagesOrLanguage, arrayOf(), decodingMode, suppressNonSpeech)
                } else {
                    throw IllegalStateException("Fallback model null")
                }
            }
        } else {
            throw IllegalStateException("No models are loaded!")
        }
    }

    /**
     * Releases this wrapper's hold on the underlying models. By default they stay cached and warm
     * for the next session ([WhisperModelCache]); pass [evict] to free the native memory now, e.g.
     * when recovering from an [OutOfMemoryError].
     */
    suspend fun close(evict: Boolean = false) = withContext(inferenceContext) {
        primaryModelGGML = null
        fallbackModelGGML = null
        releaseLeases()
        if (evict) WhisperModelCache.evictAll()
    }
}