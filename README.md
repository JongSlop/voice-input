# FUTO Voice Input (fork)

> **This is a fork of [futo-org/voice-input](https://github.com/futo-org/voice-input).** It adds `SpeechRecognizer` API support, model caching, and Bluetooth microphone support on top of upstream — see [Fork changes](#fork-changes).
>
> **The fork changes were written by an AI coding agent (Claude Code).** They were reviewed and tested on-device by the fork maintainer, but are not otherwise human-authored. FUTO is not affiliated with this fork; report fork-specific issues here, not to FUTO.

FUTO Voice Input is an application that lets you do speech-to-text on Android, integrating with third party keyboards or apps that use the generic speech-to-text APIs.

To download the application, visit the [FUTO Voice Input page](https://voiceinput.futo.org/). You can also find the contact there to report issues or suggestions.

If you have any feedback, issues are welcomed on the [public issue tracker](https://github.com/futo-org/voice-input/issues). Private inquiries are welcomed at the support email listed on the [website](https://voiceinput.futo.org/), or via the Send Feedback button in-app.

## Fork changes

Everything in this section is added by the fork. It builds on upstream and does not remove any existing functionality.

* **`SpeechRecognizer` API support.** `SpeechRecognizer.createSpeechRecognizer(context)`, `createSpeechRecognizer(context, component)`, and `createOnDeviceSpeechRecognizer(context)` are served by `WhisperRecognizerService`, so apps that use the streaming recognition API (rather than the `RECOGNIZE_SPEECH` intent) work with FUTO. `RecognitionService.onCheckRecognitionSupport` is implemented and reports the installed and supported on-device languages. Started from [PR #154](https://github.com/futo-org/voice-input/pull/154) by @davecraig, then audited and completed — partial-result gating, `EXTRA_LANGUAGE` handling, `ERROR_NO_MATCH` on empty results.
  * To use it as the system recognizer: set it under the system's assistant / voice-input settings, or `adb shell settings put secure voice_recognition_service org.futo.voiceinput/.WhisperRecognizerService`.
  * `createOnDeviceSpeechRecognizer` additionally needs the OS to resolve this app as the on-device recognizer (a system config / RRO), which some ROMs (e.g. GrapheneOS) do not expose without root.
* **Warm model cache.** Loaded Whisper models are kept resident between recognition sessions instead of being reloaded each time, and freed after a short idle period or on memory pressure. This mainly helps back-to-back use (repeated dictation, or an app driving the recognizer), and applies to both the keyboard and `SpeechRecognizer` paths.
* **Bluetooth microphone.** A new *Use Bluetooth microphone* toggle (Input settings, on by default) routes capture to a connected Bluetooth SCO / LE Audio headset mic. Without this, Android keeps recognition on the built-in mic even while a headset is connected.

## Status

Development has largely shifted focus to the [FUTO Keyboard app](https://keyboard.futo.org/), which has voice input built-in. However, FUTO Voice Input will remain available if you prefer to use it with another keyboard.

## API support

The following APIs are supported:
* `android.speech.action.RECOGNIZE_SPEECH` implicit intent, for apps and some keyboards - this opens the floating window in the center of the screen
* IME with `voice` subtype mode, for keyboards - this opens on the bottom half of the screen in place of the keyboard
* `android.speech.SpeechRecognizer` (`RecognitionService`), for apps that use the streaming recognition API - **added by this fork**, see [Fork changes](#fork-changes)

Upstream does not support the `SpeechRecognizer` API (few apps use it, and support was only planned). This fork implements it.

## Keyboard support

Keyboard support is touched on in the Help section of the app. In short, the following keyboards are supported:
* [**FUTO Keyboard**](https://keyboard.futo.org/) has FUTO Voice Input built-in; if you want to force it to use the external app you have to disable built-in voice input in its settings
* [**HeliBoard**](https://github.com/Helium314/HeliBoard)
* [**FlorisBoard**](https://github.com/florisboard/florisboard) supports it on newer releases
* [**AnySoftKeyboard**](https://github.com/AnySoftKeyboard/AnySoftKeyboard)
* [**Unexpected Keyboard**](https://github.com/Julow/Unexpected-Keyboard) (v1.23+)
* **AOSP Keyboard** available in LineageOS and others

If you're okay with using proprietary keyboards, the following are supported:
* **Grammarly Keyboard**, which uses the IME
* **Microsoft SwiftKey**, which uses the implicit intent

Incompatible keyboards:
* **Gboard** - hardcoded to use Google's voice input, does not support third-party options
* **Samsung Keyboard** - hardcoded to only allow either Samsung Voice Input, or Google Voice Input
* **Simple Keyboard** by Raimondas Rimkus - [no voice button](https://github.com/rkkr/simple-keyboard/issues/133)
* **Simple Keyboard** by Simple Mobile Tools - [no voice button](https://github.com/SimpleMobileTools/Simple-Keyboard/issues/201)
* **TypeWise** - no voice button [but suggestion filed in 2019](https://suggestions.typewise.app/suggestions/65517/voice-to-text-dictation)

## Language support

FUTO Voice Input is currently based on the OpenAI Whisper model, and could theoretically support all of the languages that OpenAI Whisper supports. However, in practice, the smaller models tend to not perform too good with languages that had fewer training hours. To avoid presenting something worse than nothing, only languages with more than 1,000 training hours are included as options in the UI:
* English
* Chinese (currently has some weird behavior between traditional/simplified)
* German
* Spanish
* Russian
* French
* Portuguese
* Korean
* Japanese
* Turkish
* Polish
* Italian
* Swedish
* Dutch
* Catalan
* Finnish
* Indonesian

Language support and accuracy may expand in the future with better optimization and fine-tuned models. Feedback is welcomed about language-related issues or general language accuracy.

## Development

You can develop this app by opening it in Android Studio. Otherwise, you can use Gradle to build the app like so:
```bash
./gradlew assembleStandaloneRelease
```

There are four build flavors:
* `dev` - for development, includes Play Store billing and all payment methods, auto-update, etc
* `playStore` - Play Store build, does not include auto-update and only includes Play Store billing
* `standalone` - does not include Play Store billing library, includes auto-update
* `fDroid` - does not include Play Store billing nor auto-update

Some prebuilt binaries are included in the `libs` directory to make the build faster, there are also instructions to build them yourself.

## License

This code is currently licensed under the [FUTO Source First License 1.0](LICENSE.md)

## Credits

The microphone icon was taken from [Feather Icons](https://feathericons.com/), an open-source icon pack authored by Cole Bemis.

Thanks to the following projects for making this possible:
* OpenAI - [OpenAI Whisper](https://github.com/openai/whisper/)
* Georgi Gerganov - [whisper.cpp](https://github.com/ggerganov/whisper.cpp)
* TensorFlow Authors - [TensorFlow Lite](https://mvnrepository.com/artifact/org.tensorflow/tensorflow-lite) (tflite was used in the past, it's no longer used)
* Max-Planck-Society - [PocketFFT](https://gitlab.mpcdf.mpg.de/mtr/pocketfft/-/blob/master/LICENSE.md)
* The WebRTC project authors - [WebRTC VAD](https://github.com/abb128/android-vad/blob/main/vad/src/main/jni/webrtc_vad/LICENSE)
* Georgiy Konovalov - [android-vad](https://github.com/abb128/android-vad)
* Other app dependencies, listed in app/build.gradle