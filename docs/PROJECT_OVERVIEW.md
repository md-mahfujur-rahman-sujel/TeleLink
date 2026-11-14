# TeleLink project overview

## Product

TeleLink is a local-network remote system with a phone controller and a TV receiver. The phone provides controls and sends shared content. The TV opens the selected app, URL, or supported media itself and can continue playback if the phone is locked or disconnected.

## Product decisions

- Build native Android applications for both the phone and TV.
- Use local Wi-Fi; basic control does not depend on a cloud service.
- Support an integrated TV browser and launch URLs in an external browser.
- Produce private APKs for device testing before considering public distribution.
- Target Android TV devices broadly and preserve compatibility across models and firmware.

## Target devices

- Any Android TV running API 26 or newer (the TV app's minimum SDK).
- Any Android phone running API 26 or newer for the remote app.
- Both devices on the same reachable local Wi-Fi network.

Behavior varies by TV firmware. Cross-app controls depend on an enabled Accessibility service, which some firmware hides or restricts; browser playback depends on the TV's codecs, DRM, and site behavior. APKs can be transferred by USB storage or installed over adb where the TV allows it.

## Current implementation

The workspace has separate `android/tv/app` and `android/mobile` applications. Implemented source flows include receiver lifecycle, QR/PIN pairing, TLS fingerprint pinning, protected reconnect credentials, device revocation, NSD discovery, keyboard/navigation/touch commands, URL/text sharing, integrated/external browser opening, app listing/launch, and TV-side HTML video controls. Both debug and release APKs build. Release APKs are signed with a private local key and copied to `builds/release/` by `scripts/build-release.ps1`.

Runtime integration, security assessment, and physical-device acceptance have not been completed. Source implementation and APK build success do not establish those results.

## Platform constraints

Cross-app input requires an explicitly enabled TV Accessibility service or a supported vendor protocol. Some apps block accessibility actions. External sites and browsers may impose DRM, codec, site-policy, or remote-control limits.

## Completion criteria

TeleLink 1.0 requires the TV and phone apps, secure pairing and device management, remote input and sharing, browser/playback paths, signed APKs, user documentation, and verified end-to-end operation. Device-specific compatibility requires installation and testing on a physical Android TV.
