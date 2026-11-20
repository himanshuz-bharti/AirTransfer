# Air Gesture Transfer

Air Gesture Transfer is an Android prototype for sharing an image with hand gestures. The sender shows a closed fist, the app looks for the image currently visible on screen in the sender's Gallery, and a nearby receiver can accept the image with an open palm.

## Demo

**Inline demo:** Upload `hand_gesture_transfer_revised.mp4` using GitHub's README editor and leave the generated attachment URL on its own line here. See [Adding the demo video](#adding-the-demo-video). The YouTube Shorts URL alone cannot play inline in this README.

## How it works

1. **Recognize a gesture.** CameraX supplies camera frames to MediaPipe Gesture Recognizer. A closed fist starts the sender flow; an open palm is used to accept an available sender offer.
2. **Capture the shared screen.** Android does not let an app silently inspect another app's screen. The sender uses Android `MediaProjection`, which asks the user for screen-capture consent, to capture the screen being shared.
3. **Find the image.** The app checks recent Gallery images against the captured screen. The native OpenCV matcher extracts ORB features, matches descriptors, and verifies candidate matches geometrically with a homography.
4. **Discover a nearby device.** Android Network Service Discovery (NSD/DNS-SD) advertises and discovers offers and transfer services on the local Wi-Fi network.
5. **Transfer the image.** The sender and receiver use a WebSocket connection to exchange transfer messages and image data in chunks. The receiver saves the image to the device's Pictures collection.

The screenshot matching is performed on the sender. The image found in the Gallery is the item sent to the receiver.

## Key technologies

- **Java Android app:** The app is built in Java and supports devices running Android 8.0 (API 26) and newer.
- **CameraX:** Provides camera frames for hand gesture recognition.
- **MediaPipe Tasks Vision:** Recognizes hand gestures using the `gesture_recognizer.task` model bundled in the app.
- **MediaProjection:** Captures the user-approved screen for matching.
- **OpenCV (C++/JNI):** Uses ORB features, descriptor matching, and homography checks to identify which Gallery image appears on screen.
- **Android NSD (DNS-SD/mDNS):** Discovers sender offers and transfer endpoints on the same local network.
- **WebSocket:** Carries the transfer protocol and chunked image data between devices.
- **Android MediaStore:** Reads Gallery images for matching and saves received images to Pictures.

## Requirements

- Android Studio with **JDK 17** for the Gradle build.
- Android SDK Platform **37** and Build Tools installed through Android Studio.
- Android **NDK** and **CMake 3.22.1** for the native OpenCV bridge.
- The **OpenCV Android SDK** downloaded and extracted locally. The SDK is not committed to this repository.
- For the two-device demo, two Android devices on the same Wi-Fi network. The app's minimum Android version is 8.0 (API 26).

## Build and run

1. Clone the repository and open it in Android Studio.
2. Install the Android SDK Platform 37, NDK, and CMake 3.22.1 from **Tools -> SDK Manager -> SDK Tools**.
3. Download and extract the OpenCV Android SDK.
4. Copy `local.properties.example` to `local.properties`, then set `opencv.sdk.dir` to the extracted OpenCV SDK directory. For example, on Windows:

   ```properties
   opencv.sdk.dir=D:/dev/OpenCV-android-sdk
   ```

   The path should point to the SDK root that contains the `sdk/native/jni` directory. `local.properties` is ignored by Git because this path is specific to each computer.

5. Sync the project with Gradle and build the debug APK:

   **Windows PowerShell**

   ```powershell
   .\gradlew.bat assembleDebug
   ```

   **macOS / Linux**

   ```bash
   ./gradlew assembleDebug
   ```

6. The debug APK is created at:

   ```text
   app/build/outputs/apk/debug/app-debug.apk
   ```

   Install it on a test device with Android Studio, or with `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

## Try the two-device flow

1. Install and open the app on both devices. Connect both to the same Wi-Fi network.
2. Grant the requested camera, overlay, media, and notification permissions. On the sender, approve Android's screen-capture prompt when it appears.
3. Show a closed fist to the device that should send. Wait for its sender offer and Gallery matching to finish.
4. Show an open palm to the device that should receive, then follow the on-screen transfer state.

Network discovery uses local-network service discovery, so both devices need to be reachable on the same Wi-Fi. Guest Wi-Fi, client isolation, or router multicast restrictions can prevent discovery.

## Permissions and privacy

The app needs camera access for gesture recognition, screen-capture consent through Android `MediaProjection`, and media access to compare Gallery images and save a received image. It also uses overlay and notification permissions for its on-screen feedback and foreground transfer service. Nearby-device discovery and WebSocket transfer happen over the local network; there is no cloud transfer service in this project.

Only start screen capture when you intend to share the visible content. Use a trusted Wi-Fi network when transferring files.

## Project structure

```text
app/src/main/
+-- assets/                  MediaPipe gesture-recognition model
+-- cpp/                     OpenCV C++ / JNI image-matching bridge
+-- java/com/example/myapplication/
|   +-- Gesture/             Gesture recognition and debouncing
|   +-- Transfer/            Sender/receiver service, NSD, matching, WebSocket transfer
|   +-- camera/              Camera frame and image utilities
|   +-- frame/               Frame detection helpers
|   +-- ui/                  Gesture and transfer overlays
|   +-- vision/              Hand landmark support
+-- res/                     Android layouts, strings, and app resources
```

## Downloading the APK

For a quick local test, use the debug APK produced by the build steps above. For sharing, download the signed APK from the [latest GitHub Release](https://github.com/himanshuz-bharti/VisionOS/releases/latest/download/app-release.apk). This download link will work after `app-release.apk` has been uploaded as an asset to a release.

See [Publishing the APK](#publishing-the-apk-on-github) for the steps.

## Current status

This is a prototype. Gesture recognition, screen capture, image matching, discovery, and transfer are implemented, but device discovery and sender/receiver coordination may behave differently across Android versions and Wi-Fi routers. Test the complete flow on the Android versions and networks you plan to demonstrate, and treat transfer reliability as active development work.

## License

There is currently no `LICENSE` file in this repository. Choose and add a license before presenting the source as open source or granting others reuse rights.

## Adding the demo video

The source video is `hand_gesture_transfer_revised.mp4`. A YouTube Shorts URL cannot play inline in a GitHub README, and a thumbnail linked to YouTube would still send readers there when clicked.

To let visitors play the video without leaving the README, upload the **original MP4 file** to GitHub and use its attachment URL:

1. Open this repository on GitHub and edit `README.md` in the web editor.
2. Put the cursor below **Inline demo** and drag `hand_gesture_transfer_revised.mp4` from your computer into the editor. GitHub uploads the file and inserts an attachment URL.
3. Keep the generated `https://github.com/user-attachments/assets/...` URL on its own line, then commit the README change. GitHub renders uploaded video attachments as an inline player.

You can also first drag the MP4 into an issue or discussion comment, copy its generated attachment URL, and paste that URL on its own line in the README. GitHub supports MP4, MOV, and WebM attachments and recommends H.264 for browser compatibility. The current upload limit for videos is 10 MB on free-plan repositories and 100 MB on paid-plan repositories. If the original file is unavailable, the YouTube link can only be presented as a link or clickable thumbnail. See GitHub's [video upload announcement](https://github.blog/changelog/2021-05-13-video-uploads-now-generally-available/) and [attachment guide](https://docs.github.com/en/get-started/writing-on-github/working-with-advanced-formatting/attaching-files).

## Publishing the APK on GitHub

1. In Android Studio, choose **Build -> Generate Signed Bundle / APK -> APK**, then click **Next**.
2. Select the `app` module. If you do not already have a release keystore, choose **Create new** and save a `.jks` keystore somewhere outside this repository. Set a key alias, strong passwords, and a validity period of at least 25 years. Back up the keystore and remember the passwords; keep both private and do not commit them to GitHub. You need the same key to sign future updates.
3. Select the `release` build variant, choose an output folder, and finish the wizard. Android Studio creates the signed APK and shows its location when the build completes. The signed file in this workspace is `app/release/app-release.apk`.
4. On GitHub, open the repository and select **Releases -> Draft a new release**.
5. Create a version tag such as `v1.0.0`, add a short release description, and upload `app/release/app-release.apk` as a release asset named exactly `app-release.apk`. The download link in this README will then work.
6. Publish the release, then replace the example release URL in this README with your repository's Releases page or the direct APK asset link.

The existing `assembleDebug` build creates a debug APK for testing; it is not the release artifact to give users. The Android Studio signing wizard creates the shareable release APK. Before publishing later app updates, increase `versionCode` in `app/build.gradle.kts` and sign with the same keystore so Android can install the update over the existing app.

The APK is about 129 MiB, so keep it as a release asset instead of committing it to normal Git history. GitHub blocks regular repository files over 100 MiB, while each release asset can be up to 2 GiB. See GitHub's documentation for [creating releases](https://docs.github.com/en/repositories/releasing-projects-on-github/managing-releases-in-a-repository), [release asset limits](https://docs.github.com/en/repositories/releasing-projects-on-github/about-releases), and [repository file limits](https://docs.github.com/en/repositories/working-with-files/managing-large-files/about-large-files-on-github).
