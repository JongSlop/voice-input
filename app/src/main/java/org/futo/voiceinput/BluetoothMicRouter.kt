package org.futo.voiceinput

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Best-effort routing of capture to a connected Bluetooth headset microphone.
 *
 * Android does not send VOICE_RECOGNITION capture to a Bluetooth SCO / LE Audio mic unless the app
 * explicitly asks for it. On API 31+ that is [AudioManager.setCommunicationDevice]; on older
 * releases it is the legacy [AudioManager.startBluetoothSco] path. Either way the link takes a
 * moment to come up, so callers should [activate], start recording, then [awaitInputDevice] and
 * apply the result with `AudioRecord.setPreferredDevice`.
 *
 * Every [activate] must be balanced with [deactivate] (idempotent) - leaving a communication device
 * set would keep the whole system routed to the headset.
 */
class BluetoothMicRouter(private val context: Context) {
    private val audioManager: AudioManager
        get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var active = false
    private var usedLegacySco = false

    private val btInputTypes = buildSet {
        add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(AudioDeviceInfo.TYPE_BLE_HEADSET)
    }

    /** Kicks off routing to a Bluetooth headset mic if one is connected. Returns true if engaged. */
    fun activate(): Boolean {
        if (active) return true

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val am = audioManager
                val target = am.availableCommunicationDevices
                    .sortedByDescending { it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
                    .firstOrNull { it.type in btInputTypes }
                    ?: return false

                active = am.setCommunicationDevice(target)
                active
            } else {
                @Suppress("DEPRECATION")
                run {
                    val am = audioManager
                    // No pre-SCO way to positively detect a headset mic; A2DP being connected is a
                    // reasonable proxy for "a Bluetooth audio device is present".
                    if (!am.isBluetoothScoAvailableOffCall || !am.isBluetoothA2dpOn) return false
                    am.startBluetoothSco()
                    am.isBluetoothScoOn = true
                }
                usedLegacySco = true
                active = true
                true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Waits (up to [timeoutMs]) for the Bluetooth mic to actually show up as a capture device.
     * Returns it so it can be set as the AudioRecord's preferred device, or null on timeout.
     */
    suspend fun awaitInputDevice(timeoutMs: Long): AudioDeviceInfo? {
        if (!active) return null
        return withTimeoutOrNull(timeoutMs) {
            var device = currentInputDevice()
            while (device == null) {
                delay(100)
                device = currentInputDevice()
            }
            device
        }
    }

    fun currentInputDevice(): AudioDeviceInfo? =
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type in btInputTypes }

    fun deactivate() {
        if (!active) return
        active = false
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            }
            if (usedLegacySco) {
                @Suppress("DEPRECATION")
                run {
                    audioManager.isBluetoothScoOn = false
                    audioManager.stopBluetoothSco()
                }
                usedLegacySco = false
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
