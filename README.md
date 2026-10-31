# Friday – always-on offline voice assistant (Android)

## Build
1. Open this folder in Android Studio (Hedgehog or newer) and let Gradle sync.
2. Download two models from https://alphacephei.com/vosk/models and unzip them into `app/src/main/assets/`:
   - a small English model (e.g. `vosk-model-small-en-in-0.4`) -> rename folder to `model-en`
   - `vosk-model-spk-0.4` -> rename folder to `model-spk`
   (Each folder needs a `uuid` file inside; run: `echo $(uuidgen) > uuid`, as the Vosk loader requires.)
3. Run on a real phone (Android 8+).

## First-time setup in the app
Save your assistant name / wake word / what it calls you -> grant the 5 steps in order -> Train my voice -> flip ON.
After that it runs in the background (foreground service + small circle) until you turn it off, and restarts after reboot.

## Voice commands
"Friday, play Casa Tupka on YouTube" - "Friday next / pause / scroll down" - "Friday go on mode" ... "go off mode"
"Friday open WhatsApp" - "open Rahul chat" - "write message" ... "send" - "what is 24 times 45"
"flashlight on" - "play music" - "tell me today's news" - "how to make tea"

## No Android Studio? Get the APK with GitHub (free)
1. Make a free account at github.com -> New repository -> "Upload files" -> drag in everything from this folder (including the hidden .github folder) -> Commit.
2. Open the Actions tab -> "Build Friday APK" -> Run workflow. Wait ~10 minutes.
3. Open the finished run -> download "Friday-APK" -> unzip -> install app-debug.apk on your phone.
