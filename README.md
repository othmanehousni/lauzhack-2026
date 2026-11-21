# LauzHack 2026

A minimal Kotlin Multiplatform app for Android and desktop. Android opens a blank white screen. Desktop opens The Mole floater, based on `mole/index.html`.

## Setup

With [mise](https://mise.jdx.dev/) installed, run from the repository root:

```sh
mise trust
mise install
```

All tool requirements and SDK setup commands are in `mise.toml`:

- Temurin JDK 21.
- Android SDK command-line tools 23.0, through mise's [Android SDK backend](https://github.com/jdx/vfox-android-sdk).
- Android SDK Platform 36, Build Tools 36.0.0, and platform tools, installed automatically by the `postinstall` hook. Accept the SDK licenses when prompted.
- Optional emulator and an API 36 Google APIs system image, installed by `android:emulator:setup`.

Mise sets `JAVA_HOME`, `ANDROID_HOME`, and tool paths for the tasks below. The Gradle wrapper downloads Gradle, Kotlin, Compose, and project dependencies on first use. Android Studio is optional.

The `postinstall` hook runs `android:setup` after `mise install`, including when Java and the command-line tools are already installed. Run `mise install` again to install any missing SDK components.

## Desktop

From the repository root:

```sh
mise run desktop
```

This opens a borderless, always-on-top floater near the bottom center of the screen. Drag the bar to move it. Right-click it, or use its system tray/menu bar icon, to reset its position or quit.

The floater displays the reference's idle state only. It has no microphone, recording, or copilot behavior.

## Android

Connect an Android phone with USB debugging enabled and accept its authorization prompt, or start an emulator. Then run:

```sh
mise run android
```

This builds, installs, and opens the Android app. Keep only one device or emulator connected when using this task.

For a local emulator on macOS or Linux, install it once:

```sh
mise run android:emulator:setup
```

Start it in a separate terminal and wait for Android to finish booting before running `mise run android`:

```sh
mise run android:emulator
```

The emulator image matches the host CPU. Virtual device files are stored in the ignored `.android/avd/` directory inside this repository. SDK packages remain in mise's Android SDK install directory, which you can inspect with `mise where android-sdk`.

## Build

After SDK setup:

```sh
mise run build
```

Outputs:

- Android APK: `androidApp/build/outputs/apk/debug/androidApp-debug.apk`.
- Desktop JAR: `composeApp/build/libs/composeApp-desktop.jar`.

## Code

- `composeApp/src/commonMain/kotlin/ch/lauzhack/app/App.kt` contains the blank screen used by Android.
- `composeApp/src/desktopMain` contains the desktop entry point, floater, and vector artwork.
- `androidApp` contains the Android launcher and application module.
- `gradle/libs.versions.toml` pins dependencies and Android build versions.
- `mise.toml` declares tools, SDK packages, and setup, run, and build tasks.
