# TeleLink communication protocol

## Transport

The TV listens for TLS WebSocket clients (`wss`) on TCP port `8765`. New phones verify the SHA-256 certificate fingerprint encoded in the TV QR before pairing. After pairing, the phone authenticates using a unique random token over TLS. The TV stores a hash of this token, not the raw token. Runtime/security review is still pending.

Discovery advertises `_telelink._tcp` with protocol version `1` and the receiver port. The phone ignores unsupported protocol versions. Discovery data is informational; it does not grant access.

## Message envelope

Commands currently use UTF-8 JSON objects with a `type` field and flat command-specific fields. Authenticated phone commands include a canonical UUID `requestId`; the receiver rejects missing or malformed IDs. For example:

```json
{
  "requestId": "7b9f66a8-5f74-4ee1-9d34-98c143f29473",
  "type": "PING",
  "text": "optional command-specific field"
}
```

Command responses use `COMMAND_RESULT`, echo `requestId`, and include `success`, `message`, and optional `data`. Pairing uses `PAIR_RESULT`; reconnect uses `AUTH_RESULT`; keepalive uses `PING`/`PONG`. The TV limits WebSocket frames and assembled text messages to 16 KiB, rejects binary messages, and rejects unsupported commands. At most 16 unauthenticated sessions may wait to pair; failed-IP tracking is bounded to 2,048 entries. A formal protocol version envelope is still a release-hardening task. Do not log PINs, tokens, or shared content.

## Pairing and authentication

1. The TV advertises discovery metadata while the receiver is running.
2. The phone scans the QR and establishes a TLS connection, trusting only the certificate fingerprint scanned from the TV.
3. Phone submits `PAIR` with the current TV PIN.
4. TV rate-limits invalid attempts and atomically consumes the PIN.
5. TV returns `PAIR_RESULT` and a unique device ID/token once; the client saves the token in encrypted Android Keystore-backed preferences.
6. TV rotates its displayed PIN for the next phone.
7. Future sessions authenticate using the device credential; no PIN is reused.
8. Revoke from the TV UI removes the stored token hash and closes live sockets for that device.

The phone must protect and never log the issued token. Pairing trust is tied to the QR's certificate fingerprint. Runtime interoperability and security review remain pending.

## Command types

| Type | Payload fields | Intended action |
|---|---|---|
| `PING` | none | Keepalive/connection check |
| `GET_DEVICE_STATUS` | none | Report receiver availability |
| `TEXT_INPUT` | `text` | Insert text in supported focused TV field |
| `KEY_PRESS` | `key` | D-pad directions, Back, Home, or Select |
| `TOUCH_GESTURE` | normalized coordinates and gesture | Accessibility gesture on TV surface |
| `SHARE_TEXT` | `text` | Enter text into a supported focused TV field |
| `OPEN_URL` | `url`, `target` (`integrated` or `external`) | Open on TV |
| `LAUNCH_APP` | `packageName` | Launch an installed TV app where permitted |
| `MEDIA_CONTROL` | `action`, optional `positionMs` | Control the first HTML video in TeleLink Browser (`PLAY`, `PAUSE`, `STOP`, `SEEK`, `SEEK_BACK`, `SEEK_FORWARD`, `VOLUME_UP`, `VOLUME_DOWN`) |
| `LIST_APPS` | none | Return launchable apps |
| TV UI action | `deviceId` | Revoke and disconnect a paired phone |

The current receiver routes the listed control commands except for `REVOKE_DEVICE`, which is a TV UI action. `GET_DEVICE_STATUS` currently reports receiver availability only; it does not report player state. Physical device behavior is not yet verified.

## Validation rules

- Require authenticated device identity for all commands except the pairing handshake.
- Validate lengths, URLs, enum values, and request IDs before dispatch.
- Permit only `http` and `https` URLs by default; reject other schemes unless an explicit safe use case is added.
- Keep callbacks and Android UI/activity operations on appropriate threads.
- Send a response that distinguishes invalid input, missing capability, permission required, app not installed, and execution failure.
