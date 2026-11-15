# Licensing notice

Umbra is a mix of **our own code** and **other people's projects**. Each part keeps its own licence.

## 1. Ours: the Grumpy Anonymous Author-First Source-Available Noncommercial License

Covered by [LICENSE.md](LICENSE.md) (marked with a header in the file where a header is possible):

- `umbra-android/`: the standalone Android client (Kotlin / Compose, uses TDLib)
- `plugins-shim/`: the Python layer that runs exteraGram-style plugins
- `scripts/`: branding and build helper scripts
- `iso/`: scripts and the first-boot server of the Umbra ISO
- `.github/workflows/`: the CI workflows
- `branding/`: the Umbra logo and icons
- Our original new files that the patches add, such as `UmbraBanners.swift`, `UmbraPoppies.swift`, `UmbraPoppies.java` and `KeepKicked.java`. These are ours, **but see section 2 below.**

Credit line (LICENSE.md §4): *Based on Umbra by Anonymous Author, licensed under the Grumpy Anonymous Author-First Source-Available Noncommercial License.*

## 2. Our code inside other people's GPL projects

The `patches/` files, and the new files our CI adds to Ghostgram / Nekogram, become part of a GPL work when they are built into the iOS app, the Android app or the ISO. For **those combined builds** the GPL applies to the whole build, and that is the only way they are distributed. Hunks of a patch that edit an existing upstream file are derived from that file and stay under its licence. This does not change what LICENSE.md says about the rest of the repository.

## 3. Not ours, and not stored in this repository

CI downloads these at build time. They keep their original licences, and nothing in LICENSE.md changes that:

| Project | Used for | Licence |
|---|---|---|
| Ghostgram (fork of Telegram-iOS) | iOS app base | GPL v2 |
| Nekogram (fork of Telegram for Android) | Android interface base | GPL v2 |
| tweb (Telegram Web K) | the web client inside the ISO | GPL v3 |
| TDLib | Telegram library of the standalone Android client | Boost Software License 1.0 |
| Python-Apple-support, CPython | Python inside the iOS app | BSD-style / PSF |
| Chaquopy | Python inside the Android app | MIT |
| Alpine Linux and its packages (Chromium, cage, Mesa, ...) | the ISO | each package's own licence |

Not affiliated with Telegram. Use your own `api_id` / `api_hash` from my.telegram.org.
