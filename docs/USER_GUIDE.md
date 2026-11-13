# TeleLink 1.0 user guide

![TeleLink logo](../assets/svg/telelink-logo-light.svg)

## What you need

- An Android TV and Android phone connected to the same Wi-Fi network.
- TeleLink TV installed on the television and TeleLink Remote installed on the phone.
- TeleLink TV remote access enabled from the TV app if you want cross-app keyboard, D-pad, or touchpad control.

TeleLink does not need a cloud account. Discovery and controls use the local network.

## First connection

1. Install and open TeleLink TV. Select **Start Receiver** and leave the app open while pairing. On Android 13 and newer, allow notifications to show the receiver notification. If you decline, the receiver still starts, but use **Stop Receiver** in the TV app when finished.
2. If Android asks for local-network access, allow it. The TV screen shows its address, a short-lived six-digit PIN, and a QR pairing code. If you revoke this access in Settings while the receiver is active, returning to TeleLink TV stops the receiver; allow access again before restarting it.
3. Open TeleLink Remote on the phone and allow local-network and camera access when prompted.
4. Scan the QR code on the TV. Confirm that the phone connects and reports successful pairing.
5. The TV rotates the PIN after pairing. The phone saves a protected credential for future reconnection; use **Forget saved TV on this phone** to remove it.

If the TV is not found, confirm both devices use the same Wi-Fi and that the router allows local device communication. Use manual TV address entry where available, then scan the TV QR so the phone can still verify its certificate fingerprint.

## Remote controls

- Use the directional pad and Select/Back/Home controls for navigation.
- Use the touch area for taps and swipes. Support depends on the TV's active Accessibility service and current screen.
- Type text into the phone field and choose **Send text** while an editable field is focused on the TV.
- Open a URL in the TeleLink browser or choose an external TV browser.
- Use **TV apps** to launch apps that Android exposes as launchable.
- Use play, pause, stop, 10-second seek, and volume buttons for HTML video opened in the TeleLink browser. Video and site support varies.
- Use Android's Share action on text or a URL and select TeleLink Remote to send it to the TV.

When TeleLink TV is in the background, Android may require a user action before another app or browser can open. If notifications are enabled, select **Open request** in the TeleLink receiver notification. If notifications are disabled, bring TeleLink TV to the foreground and send the request again.

## Enable TV remote access

From the TV screen, open Accessibility settings and enable **TeleLink TV remote access**. Android displays its system disclosure. Accessibility support is required for some cross-app actions. It does not grant control over apps that block these actions.

## Manage paired phones

TeleLink TV displays saved paired devices. Use a device's remove/revoke action on the TV to invalidate its saved credential and disconnect it. On the phone, choose **Forget saved pairing and pair again** to discard the revoked token, then scan the current TV QR code and enter a fresh pairing session.

If encrypted pairing data becomes unreadable, TeleLink stops using that registry and shows a recovery message instead of silently treating it as empty. Clear the affected app's data in Android Settings, then pair the devices again. This removes saved pairings for that app.

## Stop and restart

Select **Stop Receiver** in TeleLink TV, or use **Stop** in the receiver notification, when you do not want it to accept remote connections. Restart it before using the remote. Pairing codes expire and rotate; do not share a live QR/PIN with untrusted people.

## Known limits

- Both apps must be on a compatible Android version and the same reachable local network.
- Router guest-network isolation can block discovery or control.
- External browser controls are not guaranteed. Integrated playback depends on the site, DRM policy, codec, and TV firmware.
- App launching only covers apps the TV exposes to Android's launcher APIs.
- SIKO Android 16 acceptance remains pending until both apps are installed and exercised on that physical TV.

## APK files

The signed private APKs are in `builds/release/`. Install the TV APK only on the television and the Remote APK on the phone.

For USB installation on the TV:

1. Copy `TeleLink-TV-1.0.apk` to a USB drive and connect it to the TV.
2. Open a file manager on the TV, browse to the APK, and choose Install.
3. If Android asks, allow that file manager to install unknown apps, then confirm installation.
4. Open TeleLink TV and select **Start Receiver**.

Install `TeleLink-Remote-1.0.apk` on the Android phone using its package installer. Android may ask you to allow installs from the app used to open the APK.

These private APKs are signed with this project's local release key. Keep the signing keystore and password properties private and backed up; they are required to sign compatible updates.
