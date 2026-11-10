# Setting up MinimalTube for someone

Follow these steps in order. Tick each check before moving on. Never ask the person to paste an API key into the chat: keys are typed only into the app on the phone.

## Ask first

- Is the phone Android 8 or newer, and can it connect to this computer by USB?
- Do they want video summaries? These need a Gemini API key; the app works without one.

## Steps

1. **Tools.** Check for JDK 17 (`java -version`) and the Android SDK with platform 36 and build-tools 35 (`sdkmanager --list_installed`). Install what is missing, with the person's agreement. Set `ANDROID_HOME`, or write `sdk.dir=...` to `android/local.properties`.
   - Check: `java -version` reports 17 and the SDK folder exists.
2. **Build.** From the `android` folder run `./gradlew :app:assembleDebug` (`gradlew.bat` on Windows).
   - Check: `android/app/build/outputs/apk/debug/app-debug.apk` exists.
3. **Phone.** Ask the person to turn on Developer options and USB debugging, connect the phone, and accept the prompt on its screen.
   - Check: `adb devices` lists the phone as `device`.
4. **Install.** Run `adb install -r android/app/build/outputs/apk/debug/app-debug.apk`. If a release-signed MinimalTube is already installed, stop and ask: installing a debug build over it requires uninstalling, which deletes its data.
   - Check: the command prints `Success`.
5. **YouTube key.** Walk the person through [docs/KEYS.md](docs/KEYS.md) to create a YouTube Data API v3 key in their own Google Cloud project. They paste it into **Settings → API keys** on the phone.
   - Check: a search in the app returns videos.
6. **Channels.** Have them add a channel in **Subscriptions → Add a channel** with its `@handle`, then tap the refresh icon on Home.
   - Check: Home shows videos.
7. **Summaries (only if wanted).** Follow the Gemini section of [docs/KEYS.md](docs/KEYS.md); the key goes in the same Settings page.

Google sign-in is optional and needs the builder's own Google sign-in setup ([docs/DEVELOPER_GOOGLE.md](docs/DEVELOPER_GOOGLE.md)). Skip it unless asked; everything else works without it.

## Undo

- Remove the app: `adb uninstall app.verdant.search` (this deletes its data on the phone).
- Delete the API keys in Google Cloud Console and Google AI Studio if they are no longer needed.
- Turn USB debugging off again on the phone.
