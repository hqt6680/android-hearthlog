# Privacy Policy – HearthLog

HearthLog is an open-source, on-device Android application. It does not operate any developer backend, user account system, analytics SDK, or advertising tracker.

## What Stays on Your Device

- **Decks, Collection, Match History & Settings**: Stored locally in the app's private storage directory (`decks.json`, `collection.json`, `matches.json`, `settings.json`). Backups are JSON files exported and imported manually by you.
- **Hearthstone Logs (`Power.log` & `Decks.log`)**: Read locally on your device through user-authorized [Shizuku](https://shizuku.rikka.app/) IPC only while log tracking is active. HearthLog never accesses files outside Hearthstone's external game directories.

## Network Requests

HearthLog connects only to the following third-party endpoints over HTTPS to provide card metadata, meta snapshots, and optional in-match decision predictions:

1. **HearthstoneJSON & HearthSim** (`api.hearthstonejson.com`, `art.hearthstonejson.com`, `raw.githubusercontent.com`): Downloads public collectible card metadata, localized set names, and card tile artwork.
2. **HSReplay.net** (`hsreplay.net`): Downloads public archetype and meta deck win-rate snapshots when queried in the Meta tab.
3. **NetEase Hearthstone Box Endpoints** (`hs-api.lushi.163.com`):
   - Public constructed meta statistics (`ChinaMeta`) and aggregate opening card keep-rate statistics (`NeteaseOpeningStats`).
   - Ephemeral guest session bootstrap (`initbox`) and constructed state prediction (`standard_substep`) when live AI prediction or log replay is used.

These third-party services receive standard HTTP request metadata (such as your IP address and the match state payload required for prediction) under their respective privacy policies.
