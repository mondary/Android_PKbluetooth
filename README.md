# PK Bluetooth

Source version **2026.11.19**

> Android app that shows a custom popup when a Bluetooth device connects, records its real battery levels and exchanges files between your devices.
> Built on [VibePop](https://github.com/daloyanf/vibepop), with popup styles adapted from [BudPop](https://github.com/AweSanjam/BudPop) and battery/sharing features inspired by [btManage](https://github.com/fhnb16/btManage-android). See [third-party notices](THIRD_PARTY_NOTICES.md).

![App icon](icon.png)

[🇬🇧 English](README.md) · [🇫🇷 Français](README_fr.md)

PK Bluetooth is an independent app (`design.mondary.pkbluetooth`): it installs next to VibePop, and neither updates the other. The popup appears when a whitelisted device connects, on the lock screen too; each device can have its own media, name and duration.

## Popup preview

| Shokz | Black open-ear | Peugeot 3008 |
| :---: | :---: | :---: |
| ![Shokz popup: name, status and battery](store/gifs/popup-shokz.gif) | ![Open-ear popup: name, status and battery](store/gifs/popup-open-ear.gif) | ![Peugeot 3008 popup: name, status and battery](store/gifs/popup-peugeot.gif) |

These previews use the included visuals and the real popup layout: device name, connection status and battery. The level stays unknown (`--%`) when the device does not report it.

## Animation styles

| Minimal | Lovely | Fanciest |
| :---: | :---: | :---: |
| ![Minimal style: card with name and battery](store/gifs/style-minimal.gif) | ![Lovely style: animated entrance into a small card](store/gifs/style-lovely.gif) | ![Fanciest style: full-screen entrance before the card](store/gifs/style-fanciest.gif) |

Illustrative web previews of the three BudPop styles. Preview their actual appearance in the app.

## Download and updates

- [Explore the PK Bluetooth presentation](https://mondary.design/apps/pk-bluetooth/).

- [Download the latest signed APK](https://github.com/mondary/Android_PKbluetooth/releases/latest/download/Android_PKbluetooth.apk).
- To check from the app: Settings → App updates → **CHECK**. When a newer version is found, the app opens the signed APK download; Android's package installer asks you to confirm installation. Updates are not installed silently.
- Release signing uses the same private key for every version, so updates install over this release. Keep the key private and do not uninstall the app to update it.

## Popup and media

- The device panel opens on the active visual or animation; the selected included visual is outlined and Browse opens Android files.
- The studio separates **Visual** and **Animation** tabs. Browse each BudPop device family and play its animated preview inline before applying it.
- Battery history is visible in each device panel; Settings offers a **Default device** profile and credits and licences at the bottom. File/Wi‑Fi sharing opens general tools and does not automatically target the selected Bluetooth device.
- Videos play once and close the popup, or loop until the countdown ends.
- Three BudPop card styles — bare, lovely, fanciest — with nine device families (earbuds, headphones, speakers, soundbars, keyboards, mice, car adapters, game controllers, generic), drawn live in Kotlin. The family is detected from the device, or forced per device.
- The test button still plays the current target's exact popup configuration.

## Battery

- Only levels really reported by the device are recorded, in a local SQLite store, deduplicated, kept 30 days.
- The history lists the last 500 readings for the selected device, with a 24-hour chart; it can be shared as text.
- Devices that report nothing through Android are read through the standard GATT Battery Service (and Huami bands through their own characteristic).
- A home-screen widget shows the latest level of up to four devices.

## Sharing

- **Browser sharing**: a page served on the local network; any device with the link can take the offered files and send files or text back. Plain HTTP on a network you trust, protected by a random key, revoked when you stop.
- **Android_PKbluetooth to Android_PKbluetooth**: two installations find each other on the local network, pair once with a six-digit code, then exchange files and text both ways. Every connection is mutually authenticated with a per-installation key that never leaves the Android Keystore; a forgotten peer can never connect again.
- Received items land in a private inbox, opened or forwarded with Android's own share menu.

## Build and test

JDK 17 and an Android SDK.

```bash
./gradlew test assembleDebug
```

Signed releases and the in-app update manifest are published from GitHub Actions when a `v*` tag is pushed. The end-to-end sharing checks run on a device or emulator:

```bash
./gradlew assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w design.mondary.pkbluetooth.test/com.vibepop.qa.IntegrationRunner
```

See [CHANGELOG.md](CHANGELOG.md) for version history and the [upstream VibePop project](https://github.com/daloyanf/vibepop) for its original documentation.

## Support

[❤️ Support this project on Ko-fi](https://ko-fi.com/pouark)

## License

[MIT](LICENSE)
