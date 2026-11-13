# TeleLink 1.0 development roadmap

## Stage 0 — Product baseline

- [x] Confirm the two-app local-network product direction.
- [x] Record the SIKO TV details supplied by the user and keep device-specific validation open.
- [x] Implement a connected-device foreground receiver service, six-digit PIN expiry/rotation, attempt limiting, and status UI.

## Stage 1 — Secure TV foundation

- [x] Add WSS transport and QR-provided certificate fingerprint trust.
- [x] Add persistent paired-device credentials, reconnect authentication, and TV revoke flow.
- [x] Advertise the receiver over Android NSD while running.
- [x] Add command validation boundaries and correlated command results.
- [x] Add the opt-in TV Accessibility adapter and device management UI.
- [x] Add host-side tests for PIN lifecycle and phone pairing QR validation.
- [ ] Validate pairing, reconnect, revocation, and malformed traffic on real devices.

## Stage 2 — Phone remote

- [x] Add the Android phone app and private release signing configuration.
- [x] Add NSD discovery, QR pairing, protected credentials, reconnect, and forget flow.
- [x] Add keyboard, D-pad, touchpad, media controls, and sharesheet entry.
- [x] Add manual address fallback and user-visible status messages.
- [ ] Validate Android version and local-network permission behavior on the target phone and TV.

## Stage 3 — TV actions and playback

- [x] Add integrated WebView browser and external browser launch.
- [x] Add installed-app discovery and launch intents.
- [x] Add TV-side HTML video controls so playback is not sourced from the phone.
- [x] Route background TV app/browser launches through an explicit receiver-notification action to respect Android background activity limits.
- [ ] Validate browser, app launch, and playback support against actual TV firmware and sites.

## Stage 4 — Release hardening

- [x] Build both debug and release variants.
- [x] Configure private release signing and a release artifact/checksum script.
- [x] Add requirements, architecture, protocol, user, and release documentation.
- [x] Run host-side unit tests, full release lint, debug/release assembly, and APK signature verification.
- [ ] Run LAN, reconnect, revoke, permission, and security checks on devices.
- [ ] Install and validate both apps on the user's SIKO Android 16 TV and phone.
- [ ] Record hardware results and prepare any public-distribution follow-up.

## Completion rule

A successful Gradle build is not product acceptance. The software implementation and signed APK packaging are in place, but TeleLink 1.0 remains incomplete until end-to-end device validation is recorded, including SIKO-specific acceptance.
