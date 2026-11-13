# TeleLink 1.0 requirements

Status terms: **Done** means present in the current source; **In progress** means partial foundation exists; **Not started** means no implementation exists; **Device check** means it cannot be confirmed without the actual SIKO TV.

## Product requirements

| ID | Requirement | Priority | Baseline status |
|---|---|---:|---|
| TL-001 | Provide an Android TV receiver app and a separate Android phone remote app. | P0 | Implemented; device behavior not yet validated |
| TL-002 | Discover receiver TVs on the same local network and allow manual IP entry as fallback. | P0 | Implemented in source; runtime network check pending |
| TL-003 | Pair with a short-lived six-digit code and securely reconnect approved phones. | P0 | Implemented in source; end-to-end runtime check pending |
| TL-004 | List paired phones and allow a phone to be revoked from the TV. | P0 | Implemented in source; runtime check pending |
| TL-005 | Encrypt and authenticate control traffic. | P0 | WSS certificate fingerprint trust and token auth implemented; security review/runtime check pending |
| TL-006 | Send text from the phone's keyboard to a focused TV field. | P0 | Implemented through opt-in Accessibility actions; app compatibility varies |
| TL-007 | Provide D-pad navigation and touchpad gestures. | P0 | Implemented through opt-in Accessibility actions; touch support varies by TV |
| TL-008 | Share arbitrary text and URLs from the phone to the TV. | P0 | Implemented through Sharesheet and remote fields; runtime check pending |
| TL-009 | Open URLs in an integrated TV browser or an installed external browser. | P0 | Implemented in source; runtime/site compatibility pending |
| TL-010 | Play supported video independently on the TV and expose playback controls. | P0 | Integrated-browser HTML video controls implemented; site compatibility pending |
| TL-011 | Launch installed TV apps where Android permits. | P1 | Implemented in source; runtime check pending |
| TL-012 | Show clear connection, permission, pairing, and command errors. | P0 | Implemented in source; lifecycle/device runtime validation pending |
| TL-013 | Build debug and release APKs for both apps and provide install/use documentation. | P0 | Debug builds pass; signed private release APKs and user documentation produced |
| TL-014 | Validate the complete experience on the user's SIKO Android 16 TV. | P0 | Device check |

## Functional details

### Discovery and pairing

- TV advertises a versioned TeleLink service on the LAN.
- The phone discovers it automatically, can use a manual address, and requires the QR fingerprint to trust a new TV.
- The user approves pairing using a short-lived PIN shown on the TV.
- A phone receives a device credential for reconnecting without repeating PIN entry.
- The TV can identify and revoke saved phones. PIN attempts remain rate-limited.
- A fresh PIN is available for each additional phone; already paired phones remain connected.
- The TV stores token hashes in an encrypted Android Keystore-backed registry; the phone stores its reconnect token in encrypted Android Keystore-backed preferences.

### Remote input

- Keyboard text, D-pad directions, Back, Home, touchpad taps/gestures, and media controls are sent as typed protocol commands.
- System-wide controls require a user-enabled TV Accessibility service unless a supported vendor protocol is available.
- The app must not claim that it can control every third-party app if the TV or target app blocks accessibility actions.

### Content and playback

- URL/text sharing must work through Android Sharesheet and an in-app paste field.
- URL handling offers integrated browser and external browser choices.
- The integrated browser runs on the TV. The phone is never the media playback source.
- Direct media playback is limited to formats supported by the TV's player; protected/DRM sites may require their own app/browser.

## Non-functional requirements

- LAN control works without a TeleLink cloud account.
- Pairing secrets are not written to logs or displayed after expiry.
- Reconnection and app lifecycle changes have explicit status feedback.
- Network server operations run off the main thread; UI state updates run on the main thread.
- Release artifacts are signed with a documented key workflow; signing secrets are not committed to the project.
- User-facing documentation distinguishes verified behavior from SIKO-specific assumptions.
