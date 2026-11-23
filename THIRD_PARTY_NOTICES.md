# Third-party notices

Chrome_PKbluetooth builds on three open-source projects. Their licenses are shipped offline in the app
(Settings → Credits) and reproduced below.

## VibePop — base application

The whole app is a derivative of [VibePop](https://github.com/daloyanf/vibepop) by Fang Yan
(Yanfist): the service, receivers, popup window and lock-screen activity, the media drawer,
permissions and settings. Reused as-is, adapted, or extended for Chrome_PKbluetooth.

MIT License — Copyright (c) 2026 Fang Yan (Yanfist). Full text: `app/src/main/res/raw/vibepop_license.txt`.

## BudPop — popup styles

The card system (`FlyCard`, `BareCard`), the device families and their art (`Earbuds3D`,
`ProductArt`, `Buds`, `Kind`, `Style`) are adapted from
[BudPop](https://github.com/AweSanjam/BudPop), revision `0e9db88ef654806f6cd97d5936330702b28d7aa0`,
ported to this codebase with color fallbacks for Android 11 and older.

MIT License — Copyright (c) 2026 Sanjam (AweSanjam). Full text: `app/src/main/res/raw/budpop_license.txt`.

## btManage — battery and sharing features

The local-network browser share (`WebShare`) is adapted from
[btManage-android](https://github.com/fhnb16/btManage-android), revision
`2dbd22758c6256e05470c95b9818405297a5c4e7`; the GATT battery reader (`GattBattery`) is reused with
minor changes. The PK-to-PK exchange (`PeerHub`) is an original implementation of the pairing idea
described in btManage's README: discovery, one six-digit code, then bidirectional transfers — here
mutually authenticated by per-installation keys instead of plain HTTP.

Apache License 2.0 — Copyright the btManage authors. Full text: `app/src/main/res/raw/btmanage_license.txt`.
