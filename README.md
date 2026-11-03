# Umbra

Unofficial Telegram client (official Telegram API): iOS (rebranded Ghostgram fork + new features) and Android (TDLib).

- `patches/umbra-ios.patch` — applied by CI on top of Ghostgram (pinned commit).
- `umbra-android/` — Android client source.
- Actions: **Umbra iOS (IPA)** and **Umbra Android (APK)** — results are in the run's *Artifacts*.
- Add repository secrets `TG_API_ID` and `TG_API_HASH` (my.telegram.org) so that login works, then re-run.
- The IPA must be re-signed with your Apple ID (Sideloadly / AltStore) before installing.

GPL v2 (like Telegram iOS). Not affiliated with Telegram.
