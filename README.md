# TeleLink

![TeleLink logo](assets/svg/telelink-logo-dark.svg)

TeleLink is a local-network phone-to-TV remote system for Android TV. The phone sends controls and content; the TV opens apps, websites, or supported video itself, so TV-side playback is independent of the phone's lifecycle.

## Product goal

TeleLink 1.0 contains two native Android apps:

- **TeleLink TV:** receiver, pairing and device management, browser/playback surface, and TV-side command execution.
- **TeleLink Remote:** TV discovery, secure pairing/reconnection, keyboard, D-pad/touchpad, URL/text sharing, app launch, and playback controls.

TeleLink targets Android TV devices broadly (minimum API 26) rather than one specific model, and preserves compatibility across TV firmware. Private APK testing precedes any public distribution.

## Current state

Source implementations cover the product features listed above. The TV receiver now runs in a user-started connected-device foreground service with an ongoing notification, including when an external browser or TV app is open. Both apps have debug and release variants. The release build is configured to use the private signing key stored locally under android/tv/signing; the key and android/tv/keystore.properties are excluded from source control. The signed private APKs are published to builds/release by the build script. LAN interoperability, security review, and on-device hardware behavior still require validation.

## Build

From PowerShell at the repository root:

```powershell
.\scripts\build-release.ps1
```

For debug builds, run from android/tv:

```powershell
.\gradlew.bat :app:assembleDebug :mobile:assembleDebug
```

Back up the private signing keystore securely. Losing it prevents future updates over these APKs. Do not publish the keystore or password properties file. A successful build does not prove runtime interoperability or device compatibility.

## Product documentation

- [Requirements](docs/REQUIREMENTS.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Communication protocol](docs/COMMUNICATION_PROTOCOL.md)
- [Development roadmap](docs/DEVELOPMENT_ROADMAP.md)
- [Testing and release checklist](docs/TESTING.md)
- [User guide](docs/USER_GUIDE.md)

## Platform boundary

Cross-app text entry and navigation require a user-enabled TV Accessibility service, and some apps restrict those actions. Website playback depends on the TV's browser, codecs, DRM, and site behavior.
