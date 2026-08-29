package org.futo.voiceinput

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import org.acra.config.dialog
import org.acra.config.httpSender
import org.acra.data.StringFormat
import org.acra.ktx.initAcra
import org.acra.sender.HttpSender
import org.futo.voiceinput.ml.WhisperModelCache

class CrashLoggingApplication : Application() {
    @Suppress("DEPRECATION") // where these levels are still delivered they still mean what they say
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)

        // Drop cached models under real memory pressure. Merely going to the background is left
        // alone so back-to-back recognitions stay warm (bounded by WhisperModelCache's idle timer).
        if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            WhisperModelCache.evictAll()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        WhisperModelCache.evictAll()
    }
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)

        if(BuildConfig.ENABLE_ACRA) {
            initAcra {
                reportFormat = StringFormat.JSON

                dialog {
                    text = getString(R.string.crashed_text)
                    title = getString(R.string.crashed_title)
                    positiveButtonText = getString(R.string.crash_report_accept)
                    negativeButtonText = getString(R.string.crash_report_reject)
                    resTheme = android.R.style.Theme_DeviceDefault_Dialog
                }

                httpSender {
                    uri = BuildConfig.ACRA_URL
                    basicAuthLogin = BuildConfig.ACRA_USER
                    basicAuthPassword = BuildConfig.ACRA_PASSWORD
                    httpMethod = HttpSender.Method.POST
                }
            }
        }
    }
}