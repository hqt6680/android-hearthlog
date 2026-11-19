# Security Policy

## Security Model & Boundaries

HearthLog is designed around strict least-privilege and data-minimization principles:

1. **Scoped Shizuku File Access (`PowerLogReaderService`)**:
   - Runs as a bound Shizuku `UserService` solely to read Hearthstone's `Logs/Power.log`, `Logs/Decks.log`, and `log.config` inside known Hearthstone package directories (`com.blizzard.wtcg.hearthstone*`).
   - Does **not** execute shell commands (`Runtime.exec` / `ProcessBuilder`) and does **not** accept arbitrary file paths across the AIDL boundary.
2. **TOCTOU-Safe `log.config` Provisioning**:
   - Uses `SecureDirectoryStream` on Android API 26+ with `NOFOLLOW_LINKS`, verifies file attributes before and after writing to a temporary sibling file, and enforces strict file size limits (`64 KiB` max for `log.config`, `16 MiB` max for replay logs).
3. **Network & Parser Hardening**:
   - All network communication uses HTTPS with redirects disabled and no persistent cookie jar on prediction/keep-rate clients.
   - Log parsers enforce per-line character caps (`16,384` chars), entity count bounds (`10,000` entities), and response payload caps (`2 MiB`) to prevent memory exhaustion on malformed inputs.

## Supported Versions

| Version | Supported |
| --- | --- |
| `1.0.x` | :white_check_mark: |
| `< 1.0.0` | :x: |

## Reporting a Vulnerability

If you discover a security vulnerability (such as an IPC privilege escalation, symlink/path traversal in `PowerLogReaderService`, or parser denial-of-service), please report it via GitHub Security Advisories ("Report a vulnerability" in the Security tab) or open a redacted security issue so a fix can be coordinated before public disclosure.
