# Security

## How Permissions Are Scoped

- **Shizuku File Access (`PowerLogReaderService`)**: Only reads/writes `Logs/Power.log`, `Logs/Decks.log`, and `log.config` inside Hearthstone's package directory (`com.blizzard.wtcg.hearthstone*`). It does not execute shell commands or accept arbitrary paths over AIDL.
- **Safe `log.config` Writes**: Uses `SecureDirectoryStream` (`NOFOLLOW_LINKS`) with a temporary sibling file and attribute checks before replacing `log.config`, with size caps (`64 KiB` for `log.config`, `16 MiB` for replay logs).
- **Network & Parser Limits**: HTTPS only with redirects off and no persistent cookies on prediction clients. Log parsers cap line length (`16,384` chars), entity count (`10,000`), and HTTP response size (`2 MiB`).

## Supported Versions

| Version | Supported |
| --- | --- |
| `1.0.x` | Yes |
| `< 1.0.0` | No |

## Reporting Issues

If you find a security bug (such as path traversal or IPC privilege escalation), please open a GitHub Security Advisory rather than posting exploit details in a public issue first.
