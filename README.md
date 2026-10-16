# AirBridgeTouch

Native Kotlin Android project for direct file transfer using Android Wi-Fi Direct (Wi-Fi P2P) and TCP sockets. No cloud relay or router is used for the Wi-Fi Direct group.

## Current status

Early MVP implementation committed for review. It includes Android Wi-Fi Direct discovery and connection UI, file selection on the group client, an incoming-file accept/decline prompt on the group owner, streaming transfer, SHA-256 verification, and saving received files under Downloads/AirBridgeTouch. It has not yet been built or tested on the Pixel 10a and OPPO A78 5G. Expect iteration before everyday use.

## Requirements

- JDK 17
- Android SDK Platform 35 and Build Tools
- Android command-line tools
- Gradle 8.11.1 or compatible installation
- Both phones must support and allow Wi-Fi Direct; advertised hardware support does not guarantee every manufacturer firmware combination will connect.

## Build on Windows without Android Studio

Install JDK 17 and Android command-line tools. Set JAVA_HOME and ANDROID_HOME, then install SDK packages:

    sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"

From the project directory, generate the wrapper and build:

    gradle wrapper --gradle-version 8.11.1
    .\\gradlew.bat testDebugUnitTest
    .\\gradlew.bat assembleDebug

APK output after a successful build: app\\build\\outputs\\apk\\debug\\app-debug.apk

## First test

1. Install the same debug APK on both phones.
2. Enable Wi-Fi on both phones and open AirBridgeTouch on both.
3. Grant Nearby Wi-Fi Devices permission on Android 13+ (or Location on Android 12L and earlier).
4. Start discovery on both devices, connect one to the other, and accept any system connection prompt.
5. The phone that becomes group owner receives files. The other phone can choose and send a file. To reverse direction, disconnect/re-form the group so the other phone becomes group owner.
6. On the receiving phone, explicitly accept the incoming file. Verify the file in Downloads/AirBridgeTouch.

## Limits and known issues

- Current MVP sends from the Wi-Fi Direct group client to the group owner. Bidirectional role switching, history, cancellation, reconnect logic, and polished progress/error states are not implemented yet.
- File size is capped at 512 MiB; the sender needs a provider-reported file size.
- Keep both phones awake and the app open during tests.
- Some manufacturer firmware restricts Wi-Fi P2P or concurrent infrastructure Wi-Fi. This app cannot guarantee that router Wi-Fi stays connected on every phone during Wi-Fi Direct.
- INTERNET permission is used for local TCP sockets; no cloud service is configured.
- Test with non-sensitive sample files first. Do not rely on this MVP for important files until device-to-device tests pass.
