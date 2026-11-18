# TeleLink

![TeleLink logo](assets/svg/telelink-logo-dark.svg)

**A local-network remote for Android TV.** TeleLink pairs an Android phone with an Android TV so you can navigate, type, share links, launch apps, and control supported video from the phone.

The project contains two native Android applications:

- **TeleLink TV** runs on the television. It provides the receiver, pairing screen, paired-device management, and a built-in browser.
- **TeleLink Remote** runs on the phone. It discovers and pairs with the TV, then provides remote controls and sharing tools.

TeleLink communicates over the local network and does not require a cloud account.

## Features

- Pair by scanning a QR code and confirming a short-lived PIN.
- Reconnect to saved TVs using protected pairing credentials.
- Use D-pad, touchpad, keyboard, media, and volume controls.
- Share text or URLs, launch available TV apps, and open links in a TV browser.
- Use the phone as a Bluetooth keyboard, mouse, and media remote on supported devices.
- Choose light or dark appearance in either app's Settings screen.

Cross-app navigation and text entry on the TV require enabling TeleLink's Accessibility service. Some TV apps restrict remote input. Browser playback depends on the site, codecs, DRM, and TV firmware.

## Requirements

- Android phone and Android TV running Android 8.0 (API 26) or later.
- A reachable local network shared by both devices for Wi-Fi pairing and control.
- Android Studio with the Android SDK to build and run the apps.
- Java 25 for the configured Gradle toolchain. The Gradle wrapper can provision the toolchain when required.

Bluetooth remote mode requires Android 9 or later on the phone and Bluetooth support on the TV. Phone hardware and manufacturer software may limit Bluetooth HID support.

## Build and run

Open the `android/tv` directory in Android Studio. It is the Gradle project root and includes both the `app` (TeleLink TV) and `mobile` (TeleLink Remote) modules. Select the module you want to run and a matching Android TV or phone device/emulator, then click **Run**.

To build both debug APKs from PowerShell at the repository root:

```powershell
Set-Location android/tv
.\gradlew.bat :app:assembleDebug :mobile:assembleDebug
```

The APKs are written to:

```text
android/tv/app/build/outputs/apk/debug/app-debug.apk
android/mobile/build/outputs/apk/debug/mobile-debug.apk
```

Install the TV APK on an Android TV and the Remote APK on an Android phone. For Wi-Fi setup, connect both devices to the same network, start the receiver in TeleLink TV, then scan the TV's QR code in TeleLink Remote. See the [user guide](docs/USER_GUIDE.md) for permissions, pairing, Bluetooth mode, and troubleshooting.

## Release builds

Private release builds are created from PowerShell at the repository root:

```powershell
.\scripts\build-release.ps1
```

The script builds, verifies, and copies the signed APKs to `builds/release/`, then writes `SHA256SUMS.txt`. It requires the private signing configuration at `android/tv/keystore.properties` and the corresponding keystore. These files are intentionally excluded from source control. Keep secure backups; losing the signing key prevents compatible updates, and neither the keystore nor its password file should be shared.

## Project status

Both apps have debug and release build variants. Device-level validation is still needed for pairing, reconnection, permissions, TV firmware compatibility, app launching, and playback. A successful build alone does not confirm behavior on physical devices.

## Documentation

- [User guide](docs/USER_GUIDE.md)
- [Requirements](docs/REQUIREMENTS.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Communication protocol](docs/COMMUNICATION_PROTOCOL.md)
- [Development roadmap](docs/DEVELOPMENT_ROADMAP.md)
- [Testing and release checklist](docs/TESTING.md)
