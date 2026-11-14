# TeleLink testing and release checklist

## Build verification

Run from PowerShell in the repository root:

```powershell
.\scripts\build-release.ps1
```

This compiles/packages both release variants, verifies APK signatures with Android SDK `apksigner`, checks each APK's package ID, version, minimum/target SDK, and launcher metadata with `aapt`, copies the APKs to `builds/release/`, and records SHA-256 hashes. Debug builds can be produced from `android/tv`:

```powershell
.\gradlew.bat :app:assembleDebug :mobile:assembleDebug
```

Build verification proves compilation, packaging, and (for release) APK signature validity. It does not prove runtime behavior, security, or target-device compatibility.

A static source search found no Android `Log` calls, `System.out`/`System.err` writes, or `printStackTrace()` calls in either app's main source. Check release logcat on a device as part of acceptance, because runtime libraries and platform components are outside this source search.

The current local verification also runs the TV pairing lifecycle and command request-ID tests, phone QR payload validation tests, and full release lint for both apps:

```powershell
cd android/tv
.\gradlew.bat :app:testDebugUnitTest :mobile:testDebugUnitTest :app:lintRelease :mobile:lintRelease
```

These host-side tests cover PIN generation/expiry/one-time rotation, monotonic per-IP failed-pair throttling and bounded-table eviction, TV disconnect reason reporting, exact QR certificate fingerprint trust and mismatch rejection, canonical command request IDs, the 16 KiB WebSocket frame limit, one-time authorization for notification launches, stale-discovery generation guards, IPv4 receiver endpoint selection, phone-side HTTP(S) URL validation, and QR scheme, host, port, fingerprint, PIN, and duplicate-parameter validation. A local TLS WebSocket integration test exercises pre-pair command rejection, pairing, successful saved-token reconnect, malformed request-ID rejection, authenticated command routing, immediate authorization invalidation and active-session closure on revocation, revoked-token rejection, malformed JSON, binary-frame rejection, oversized-frame closure, live failed-PIN throttling, and receiver stop-status propagation. Router/LAN behavior, Android Keystore integration, Accessibility controls, browser playback, and device compatibility still require device testing.

## Pairing and device tests

- Start/stop the TV receiver repeatedly; verify receiver state, PIN, and discovery advertisement agree.
- Move the TV app to the background by opening an external browser and another TV app; verify the receiver notification and phone connection remain active until Stop is selected.
- Pair one phone; confirm the PIN rotates and connected count increments.
- Pair another phone while the first remains connected.
- Verify wrong PIN attempts are rate-limited and the block duration is enforced.
- Verify PIN expiration/renewal while the receiver remains active.
- Restart apps and reconnect with a saved phone credential without reusing the PIN.
- Revoke a phone and verify the old credential cannot reconnect or send commands.
- Confirm production builds do not log PINs, tokens, clipboard content, or shared URLs.

## Network and security checks

- On Android 17/API 37, deny local-network access and verify receiver startup, discovery, pairing, and saved-device reconnect stay blocked with clear guidance. Grant it and repeat the connection paths, including manual IP reconnect. Revoke it in Settings while the remote is open and verify the connection entry point refuses to open a socket.
- On Android 17/API 37, revoke local-network access in Settings while the TV receiver is active. Return to TeleLink TV and verify the receiver stops, the pairing PIN clears, and the status asks for permission to be restored before restarting.
- On Android 16/API 36, verify these flows work without prompting for the Android 17 local-network permission.
- Discover the TV on a typical Wi-Fi router and connect using the QR/manual-address path.
- Stop and restart discovery quickly while a TV is appearing or disappearing; old results must not reappear in the new search.
- Verify devices on separate networks cannot reach the receiver.
- Verify certificate fingerprint mismatch is rejected and all remote traffic uses WSS.
- Test malformed, oversized text and binary frames, unsupported, and unauthorized messages.
- Test router client isolation and multicast-disabled behavior and check the displayed recovery guidance.

## Feature checks

- Send text into supported TV fields and exercise D-pad, Back, Home, and Select.
- Test touchpad tap and swipe behavior on supported TV screens.
- Share plain text and URLs through Android Sharesheet.
- Open URLs in integrated and external browser modes.
- Launch a known installed app and handle missing or restricted packages.
- Background TeleLink TV, request an app or browser launch from the phone, and verify the notification's **Open request** action launches it. Repeat with notifications denied and verify the phone receives clear guidance to bring TeleLink TV forward and retry.
- Start supported TV-side video, control it from the phone, then lock/leave the phone app; playback should continue.
- Confirm DRM, codec, website, and external-browser limitations produce honest status feedback.

## Target-device acceptance

On a physical Android TV and Android phone, record:

- TV firmware version and app installation/launch behavior.
- Wi-Fi address, NSD discovery, QR pairing, reconnect, and revocation.
- Accessibility setup and remote input behavior.
- Browser/app launch behavior and media playback/control results.
- Any permissions, firmware limitations, or failures.

This acceptance remains pending until results from the physical devices are recorded.

## Signing key handling

The private signing keystore is stored locally in `android/tv/signing/telelink-private-release.jks` and ignored by Git; passwords are in ignored `android/tv/keystore.properties`. Back up both privately, do not attach them to APKs or commit them, and preserve the same key for future private APK updates. A later public distribution may require a separate release ownership/signing decision.
