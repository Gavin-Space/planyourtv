# Parent phone shell

The repository contains an optional Android phone app (`:parent`) that opens the Parent Center
page served by the TV over the local network. It is a browser shell, not a second parent app: the
page, its PIN gate and its data stay on the TV.

## What it does

- Scans the QR code shown in Parent Center on the TV, or accepts the complete address encoded
  in that code. The scanner uses a CameraX preview and native zxing-cpp decoding, with continuous
  focus, tap-to-focus, zoom and a torch.
- Remembers each paired TV, so the code is scanned once per TV. The device list can hold several
  TVs, switch between them, and give each one a name. Names and the list are stored only on the
  phone; nothing is registered with the TV or a cloud service.
- Shows the page full screen in a WebView, with the browser behavior the page relies on:
  file import, JSON export through the system save dialog, copy to clipboard, and external links
  in the system browser.
- Refreshes the page, opens the device list, and can pair another TV from the menu.
- Checks for parent-app updates. A newer version is downloaded from the official release,
  verified against its published SHA-256, package name, version and signing certificate, then
  handed to Android's installer, which still asks the user to confirm.

The app has no account, analytics or cloud storage. It connects to the paired TV or TVs over the
local network. When checking for updates it also requests the release `notes.json` and the
optional parent APK from `planyourtv.me`, falling back to the project's GitHub Releases; it does
not upload TV data, parent-page content or the device list. Its permissions are `INTERNET`,
`CAMERA` and `REQUEST_INSTALL_PACKAGES`; the last one is used only for the app's own update.

The admin session for each TV is stored in the WebView's per-origin local storage, exactly as it
would be in a browser. Switching TVs keeps each session separate. If a TV's IP address changes,
its storage origin changes too, so that TV asks for the PIN once at the new address. Uninstalling
the app or clearing its data removes the sessions and the device list.

## What it does not do

- It does not include TV-native settings that are not part of the web page, such as initial
  binding, migration, or guard-service controls.
- It does not work when the phone and the TV are on different networks, or when the TV's local
  Parent Center is not running.
- It is not a TV APK and is not installed by the TV installer. The TV release still contains only
  `standard` / `guard`; the parent app is a separate optional download.
- It does not install updates silently, and it never downloads or installs a TV APK. Android
  shows its normal install confirmation every time.

## Download

The official optional build is served from the project site:

```text
https://planyourtv.me/download/planyourtv-parent.apk
```

GitHub fallback:

```text
https://github.com/heimi51168/planyourtv/releases/latest/download/planyourtv-parent.apk
```

It requires Android 7.0 (API 24) or newer. Open the link in the phone's browser, allow the browser
to install unknown apps, install the APK, and open it. The phone and TV must be on the same Wi-Fi
when pairing. This is not a TV APK: do not install it on the television.

Once installed, the app can update itself: use “Check for updates” in the menu, or accept the
banner when a newer version is found. Android asks for permission to install unknown apps the
first time; without it, the update is not downloaded or installed. The update keeps the paired
TV list and each TV's session.

## Build

The module targets Android 7.0 (API 24) and newer. It is built with the same Gradle wrapper as the
TV app:

```bash
./gradlew :parent:assembleDebug :parent:testDebugUnitTest
adb install -r parent/build/outputs/apk/debug/parent-debug.apk
```

A release build (R8 and resource shrinking) is produced the same way:

```bash
./gradlew :parent:assembleRelease
```

The release build reads `local/parent-signing.properties`, separate from the TV app's key. Without
one, a local release build is signed with the local debug key and must not be distributed as an
update. Official builds must use a stable parent-only key; never reuse the TV release key.

## Pairing

1. On the TV, open Parent Center so the QR code is visible.
2. On the phone, open the app and tap the square scan button. If the camera is unavailable, tap
   "Can't scan? Enter the address manually" and type the complete address encoded in the QR code,
   including the IP, port and the code after them (for example
   `192.0.2.10:8790/abcd2345/quiz`).
3. Use the menu to refresh, open the page in the system browser, or open “My devices” to switch
   TVs, rename or remove one, or add another TV by scanning or entering its address.
4. If the TV's IP address changes, scan its QR code again: the app updates that TV's entry instead
   of adding a duplicate. Removing a TV from the phone does not change anything on the TV.

The phone and the TV must be on the same Wi-Fi network. If the page cannot be reached, check that
Parent Center is open on the TV and that its address still matches; scan the new QR code after a
DHCP address change.

See [BUILDING.md](BUILDING.md) for the general toolchain and build requirements.
