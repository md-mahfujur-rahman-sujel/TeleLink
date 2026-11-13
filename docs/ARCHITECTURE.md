# TeleLink architecture

## System view

```text
Android phone (TeleLink Remote)
  discovery + pairing + remote UI + sharesheet
                │ local Wi-Fi / authenticated encrypted transport
                ▼
Android TV (TeleLink TV)
  foreground receiver service + device registry + command router
       ├─ Accessibility adapter for supported cross-app controls
       ├─ integrated browser / TV-side playback
       ├─ Android intents for external browser and app launch
       └─ media-session controls where the target exposes them
```

The receiver runs in a user-started connected-device foreground service, so it remains available while an external browser or TV app is in front. Android limits activity launches from background services; URL and app requests received in that state are exposed as a user-confirmed **Open request** notification action. The action carries a one-time, in-process authorization token, so another app cannot forge a request by invoking the exported launcher activity. If notification access is unavailable, the phone reports that TeleLink TV must be brought forward before retrying. The phone is not the playback device. Once a URL or media request is accepted, execution belongs to the TV.

## Applications and modules

- `android/tv/app`: Android TV receiver and TV execution surface.
- `android/mobile`: Android phone remote with QR scanner, NSD discovery, encrypted credential storage, sharesheet input, and remote controls.
- The message types are currently mirrored in the two apps; centralize them in a shared module before protocol evolution.

Both apps use Kotlin and Jetpack Compose. Keep transport, device registry, command routing, and UI state separate so controls can be verified independently.

## Discovery

Use Android Network Service Discovery (NSD/mDNS) to advertise and find a versioned `_telelink._tcp` receiver. The service record contains a stable app identifier and protocol version, not a PIN or secret. Manual host/port entry remains available for routers that block multicast.

## Transport and trust

The foreground service owns the TLS WebSocket receiver (`wss`) on TCP port `8765`, advertises it, and publishes a persistent notification with a Stop action. It uses Android connected-device foreground-service type for its local-network connection. See [Android foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types). The TV creates a self-signed RSA identity in Android Keystore. The TV QR contains the SHA-256 certificate fingerprint and short-lived PIN; the phone verifies the exact certificate before sending the PIN. After pairing, the TV stores only a hash of a random device token in its AES-GCM-encrypted Keystore-backed registry. The phone stores its token in AES-GCM-encrypted Keystore-backed preferences. Revocation removes the TV token hash and closes live sockets for that device.

This is a local, QR-trusted identity scheme. A certificate reset requires scanning a new QR code. Protocol security still requires independent review and runtime testing before public release.

## Pairing and device management

- A TV pairing session issues a cryptographically random six-digit code that expires after five minutes.
- Successful pairing rotates the displayed code so another phone can be paired without disconnecting existing clients.
- A successful phone receives a unique, revocable reconnect credential.
- TV stores device records and credentials using Android Keystore-backed encryption.
- Phone stores its credential using Android Keystore-backed encrypted storage.
- If encrypted pairing data cannot be read, the apps fail closed and report a recovery action instead of replacing the unreadable registry with an empty one.
- TV UI shows connected phones separately from saved devices and offers revoke/remove actions.

The `PairingManager` manages the short-lived code; `PairedDeviceStore` persists device names and token hashes. PINs rotate after a successful pair and renew while the receiver remains active.

## Command execution

The receiver limits WebSocket frames and assembled text messages to 16 KiB, rejects binary messages, and gates commands on a paired device token. A command router maps input to focused controls, accessibility actions, browser actions, app intents, or media actions. It returns a `COMMAND_RESULT` with `requestId`, success, and a safe message; accepting a frame is not proof that the requested action succeeded. Authorization is checked again on the TV main thread immediately before command execution, so a command queued before device revocation cannot execute afterward.

An Android Accessibility service is opt-in and must be clearly disclosed. Use supported node actions and gestures; do not promise raw global key injection. Browser opening uses Android intents, with an explicit activity intent for the integrated browser and an `ACTION_VIEW` intent for external browsers. App launch handles missing activities and background-launch restrictions.

## Browser and media

- Integrated mode hosts browsing/playback on the TV in a dedicated activity.
- External mode launches an installed browser with an `ACTION_VIEW` intent.
- The integrated WebView can control the page's first HTML video element for play/pause/stop/seek/volume; no cross-app Android media-session control is implemented.
- The phone sends controls and may disconnect without stopping playback.
- DRM, unsupported codecs, site restrictions, external browser controls, and TV firmware behavior remain compatibility limits.

## Android compatibility

The TV is reported to run Android 16. Google TV Remote test returned `no_remote`; the exact Android TV Remote Service installation is unconfirmed. TeleLink must work through its own companion app and must not depend exclusively on Google's remote service. The manifest targets API 37. The TV requests local-network permission before receiver startup, and the phone gates discovery, pairing, and saved-device connections on API 37+; the connection entry point also checks permission before opening a socket. Verify this behavior on supported devices before release.
