package org.futo.voiceinput

import android.content.Context
import android.content.ContextParams
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.RecognitionSupport
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.coroutineScope
import kotlinx.coroutines.launch
import org.futo.voiceinput.ml.RunState
import org.futo.voiceinput.settings.LANGUAGE_TOGGLES
import org.futo.voiceinput.settings.MULTILINGUAL_MODEL_INDEX
import org.futo.voiceinput.settings.getSetting
import org.futo.voiceinput.settings.getSettingBlocking
import java.util.Locale

class WhisperRecognizerService : RecognitionService(), LifecycleOwner {
    private val mLifecycleRegistry = LifecycleRegistry(this)

    override val lifecycle: Lifecycle
        get() = mLifecycleRegistry

    override fun onCreate() {
        super.onCreate()
        mLifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        mLifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        mLifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onDestroy() {
        cancelCurrent()
        mLifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    private var currentRecognizer: AudioRecognizer? = null

    private fun cancelCurrent() {
        currentRecognizer?.cancelRecognizer()
        currentRecognizer = null
    }

    private fun safeCallback(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun fail(callback: Callback, error: Int) {
        safeCallback { callback.error(error) }
        currentRecognizer = null
    }

    /**
     * Whisper always ships the English-39 model as a builtin asset, so English is always available.
     * Any other language additionally requires the multilingual model to have been downloaded.
     */
    private suspend fun installedLanguages(): List<String> {
        val result = linkedSetOf("en")

        val multilingualModel = MULTILINGUAL_MODELS.getOrNull(getSetting(MULTILINGUAL_MODEL_INDEX))
        if (multilingualModel != null && !modelNeedsDownloading(multilingualModel)) {
            result.addAll(getSetting(LANGUAGE_TOGGLES))
        }

        return result.toList()
    }

    private fun resolveForcedLanguage(intent: Intent?): String? {
        val tag = intent?.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE) ?: return null
        val language = Locale.forLanguageTag(tag.replace('_', '-')).language
        if (language.isNullOrBlank()) return null
        if (language == "en") return "en"

        // Forcing a non-English language only works if the multilingual model is already present,
        // otherwise loading it would abort recognition to open the downloader.
        val multilingualIdx = getSettingBlocking(
            MULTILINGUAL_MODEL_INDEX.key, MULTILINGUAL_MODEL_INDEX.default
        )
        val multilingualModel = MULTILINGUAL_MODELS.getOrNull(multilingualIdx) ?: return null
        if (modelNeedsDownloading(multilingualModel)) return null

        return if (LANGUAGE_LIST.any { it.id == language }) language else null
    }

    override fun onStartListening(intent: Intent?, callback: Callback?) {
        if (callback == null) return
        cancelCurrent()

        val attributionContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            createContext(
                ContextParams.Builder()
                    .setNextAttributionSource(callback.callingAttributionSource)
                    .build()
            )
        } else {
            this
        }

        val wantPartialResults =
            intent?.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false) ?: false

        val biasingStrings: List<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent?.getStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS) ?: emptyList()
            } else {
                intent?.getStringArrayListExtra("android.speech.extra.BIASING_STRINGS") ?: emptyList()
            }

        val forcedLanguage = resolveForcedLanguage(intent)

        currentRecognizer = object : AudioRecognizer() {
            override val context: Context
                get() = this@WhisperRecognizerService
            override val recordingContext: Context
                get() = attributionContext
            override val lifecycleScope: LifecycleCoroutineScope
                get() = this@WhisperRecognizerService.lifecycle.coroutineScope

            private var hasStartedSpeech = false

            override fun cancelled() {
                fail(callback, SpeechRecognizer.ERROR_CLIENT)
            }

            override fun finished(result: String) {
                if (result.isBlank()) {
                    fail(callback, SpeechRecognizer.ERROR_NO_MATCH)
                    return
                }

                val bundle = Bundle().apply {
                    putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(result))
                    putFloatArray(SpeechRecognizer.CONFIDENCE_SCORES, floatArrayOf(1.0f))
                }
                safeCallback { callback.results(bundle) }
                currentRecognizer = null
            }

            override fun languageDetected(result: String) {}

            override fun partialResult(result: String) {
                if (!wantPartialResults || result.isBlank()) return

                val bundle = Bundle().apply {
                    putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(result))
                }
                safeCallback { callback.partialResults(bundle) }
            }

            override fun decodingStatus(status: RunState) {}

            override fun loading() {}

            override fun needPermission() {
                fail(callback, SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
            }

            override fun permissionRejected() {
                fail(callback, SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
            }

            override fun recordingStarted() {
                safeCallback { callback.readyForSpeech(Bundle()) }
            }

            override fun updateMagnitude(magnitude: Float, state: MagnitudeState) {
                safeCallback {
                    callback.rmsChanged(magnitude * 10.0f)
                    if (!hasStartedSpeech && state == MagnitudeState.TALKING) {
                        hasStartedSpeech = true
                        callback.beginningOfSpeech()
                    }
                }
            }

            override fun processing() {
                safeCallback { callback.endOfSpeech() }
            }
        }.also {
            it.extraBiasingWords = biasingStrings
            if (forcedLanguage != null) it.forceLanguage(forcedLanguage)
            it.create()
        }
    }

    override fun onStopListening(callback: Callback?) {
        currentRecognizer?.finishRecognizerIfRecording()
    }

    override fun onCancel(callback: Callback?) {
        cancelCurrent()
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCheckRecognitionSupport(
        recognizerIntent: Intent,
        supportCallback: SupportCallback
    ) {
        lifecycle.coroutineScope.launch {
            try {
                val installed = installedLanguages()
                val supported = LANGUAGE_LIST.map { it.id }

                val support = RecognitionSupport.Builder()
                    .setInstalledOnDeviceLanguages(installed)
                    .setPendingOnDeviceLanguages(emptyList())
                    .setSupportedOnDeviceLanguages(supported)
                    .setOnlineLanguages(emptyList())
                    .build()

                supportCallback.onSupportResult(support)
            } catch (e: Exception) {
                e.printStackTrace()
                supportCallback.onError(SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT)
            }
        }
    }
}
