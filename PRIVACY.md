# Privacy

HearthLog runs locally on your device. There is no developer server, no user login, no analytics SDK, and no ads.

## Stored Locally on Your Device

- **Decks, Collection, Match History & Settings**: Saved in the app's private storage (`decks.json`, `collection.json`, `matches.json`, `settings.json`). You can export or import them manually as JSON.
- **Hearthstone Logs (`Power.log` & `Decks.log`)**: Read on-device via Shizuku only while tracking is active. HearthLog does not access files outside Hearthstone's data directory.

## Network Requests

HearthLog only talks to these public/third-party endpoints over HTTPS:

1. **HearthstoneJSON & HearthSim** (`api.hearthstonejson.com`, `art.hearthstonejson.com`, `raw.githubusercontent.com`): Card database and card tile images.
2. **HSReplay.net** (`hsreplay.net`): Meta archetype and deck win-rate snapshots when you browse the Meta tab.
3. **NetEase Hearthstone Box** (`hs-api.lushi.163.com`): Public China-server meta stats, opening keep rates, and live/replay match predictions (using temporary guest requests).
