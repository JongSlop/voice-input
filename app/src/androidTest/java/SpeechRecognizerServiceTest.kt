import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Exercises [org.futo.voiceinput.WhisperRecognizerService] through the real framework
 * [SpeechRecognizer] client, over all three entry points the platform exposes:
 *
 *  - [SpeechRecognizer.createSpeechRecognizer] with an explicit component
 *  - [SpeechRecognizer.createSpeechRecognizer] resolving the default service
 *  - [SpeechRecognizer.createOnDeviceSpeechRecognizer]
 *  - [SpeechRecognizer.checkRecognitionSupport] -> onCheckRecognitionSupport
 *
 * These assert the wiring (bind, attribution, mic access, lifecycle callbacks), not
 * transcription accuracy - there is no way to feed real audio into AudioRecord from a test.
 */
@RunWith(AndroidJUnit4::class)
class SpeechRecognizerServiceTest {
    @get:Rule
    val permission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    private val component
        get() = ComponentName(context.packageName, "org.futo.voiceinput.WhisperRecognizerService")

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    /** Collects callbacks from a single recognition session. */
    private class Recorder : RecognitionListener {
        val ready = CountDownLatch(1)
        val done = CountDownLatch(1)
        val rmsChanges = AtomicInteger(0)
        val lastError = AtomicReference<Int?>(null)
        val results = AtomicReference<Bundle?>(null)
        val errorBeforeReady = AtomicReference<Int?>(null)

        override fun onReadyForSpeech(params: Bundle?) { ready.countDown() }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) { rmsChanges.incrementAndGet() }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onPartialResults(partialResults: Bundle?) {}

        override fun onError(error: Int) {
            if (ready.count > 0) errorBeforeReady.set(error)
            lastError.set(error)
            done.countDown()
        }

        override fun onResults(res: Bundle?) {
            results.set(res)
            done.countDown()
        }
    }

    private fun runSession(create: () -> SpeechRecognizer): Recorder {
        val recorder = Recorder()
        val holder = AtomicReference<SpeechRecognizer>()

        onMain {
            val sr = create()
            holder.set(sr)
            sr.setRecognitionListener(recorder)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            }
            sr.startListening(intent)
        }

        val gotReady = recorder.ready.await(20, TimeUnit.SECONDS)

        // Give it a moment of (silent) audio, then ask for the final result.
        Thread.sleep(1500)
        onMain { holder.get().stopListening() }

        recorder.done.await(30, TimeUnit.SECONDS)
        onMain { holder.get().destroy() }

        assertTrue("never received onReadyForSpeech", gotReady)
        return recorder
    }

    private fun assertHealthySession(recorder: Recorder) {
        assertTrue(
            "recording pipeline never produced rmsChanged callbacks",
            recorder.rmsChanges.get() > 0
        )
        assertFalse(
            "got a permission error - attribution / mic wiring is broken",
            recorder.errorBeforeReady.get() == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
        )
        val err = recorder.lastError.get()
        val results = recorder.results.get()
        // Silence in -> either an empty/no-match result or a NO_MATCH/timeout error is fine.
        // A CLIENT / permission / network error is not.
        assertTrue(
            "session ended abnormally: error=$err results=$results",
            results != null ||
                err == SpeechRecognizer.ERROR_NO_MATCH ||
                err == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
        )
    }

    @Test
    fun explicitComponent_startListening_isHealthy() {
        assertHealthySession(runSession {
            SpeechRecognizer.createSpeechRecognizer(context, component)
        })
    }

    @Test
    fun defaultService_startListening_isHealthy() {
        val current = android.provider.Settings.Secure.getString(
            context.contentResolver, "voice_recognition_service"
        )
        assumeTrue(
            "default voice_recognition_service is not FUTO (got: $current)",
            current != null && ComponentName.unflattenFromString(current) == component
        )
        assertHealthySession(runSession {
            SpeechRecognizer.createSpeechRecognizer(context)
        })
    }

    /**
     * The on-device entry point. [SpeechRecognizer.createOnDeviceSpeechRecognizer] is gated by the
     * framework on the `config_defaultOnDeviceSpeechRecognitionService` resource; when the OS points
     * that at this package the framework binds [org.futo.voiceinput.WhisperRecognizerService]
     * exactly like the explicit-component case above, so this asserts the same health contract.
     *
     * On an OS that ships no on-device recognizer and leaves that resource empty (e.g. stock
     * GrapheneOS), `createOnDeviceSpeechRecognizer` throws before any binding happens and there is
     * no non-root way to change it - the test is skipped in that case.
     */
    @Test
    fun onDeviceRecognizer_startListening_isHealthy() {
        try {
            val probe = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            onMain { probe.destroy() }
        } catch (e: UnsupportedOperationException) {
            assumeNoException("on-device recognition is not configured on this OS", e)
            return
        }
        assertHealthySession(runSession {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        })
    }

    @Test
    fun backToBackSessions_secondIsNotSlower_andReusesWarmModel() {
        fun timedSession(): Long {
            val start = System.currentTimeMillis()
            val recorder = runSession { SpeechRecognizer.createSpeechRecognizer(context, component) }
            val elapsed = System.currentTimeMillis() - start
            assertHealthySession(recorder)
            return elapsed
        }

        val first = timedSession()
        val second = timedSession()
        android.util.Log.i("SRTest", "session durations: first=${first}ms second=${second}ms")

        // The warm session must not be meaningfully slower than the cold one.
        assertTrue(
            "second (warm) session $second ms was much slower than first (cold) $first ms",
            second <= first + 1500
        )
    }

    @Test
    fun checkRecognitionSupport_reportsEnglishInstalled() {
        val support = AtomicReference<RecognitionSupport?>()
        val error = AtomicReference<Int?>()
        val latch = CountDownLatch(1)

        onMain {
            val sr = SpeechRecognizer.createSpeechRecognizer(context, component)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
            }
            sr.checkRecognitionSupport(
                intent,
                context.mainExecutor,
                object : RecognitionSupportCallback {
                    override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                        support.set(recognitionSupport)
                        latch.countDown()
                    }

                    override fun onError(errorCode: Int) {
                        error.set(errorCode)
                        latch.countDown()
                    }
                }
            )
        }

        assertTrue(
            "checkRecognitionSupport never called back",
            latch.await(20, TimeUnit.SECONDS)
        )
        assertTrue("onCheckRecognitionSupport returned error ${error.get()}", support.get() != null)

        val s = support.get()!!
        assertTrue(
            "English not reported installed: installed=${s.installedOnDeviceLanguages} " +
                "supported=${s.supportedOnDeviceLanguages}",
            s.installedOnDeviceLanguages.any { it.startsWith("en") }
        )
        assertTrue(
            "supported language list is empty",
            s.supportedOnDeviceLanguages.isNotEmpty()
        )
    }
}
