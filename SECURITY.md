# Security policy

Please report vulnerabilities privately through
[GitHub's private vulnerability reporting](https://github.com/ashusnapx/news/security/advisories/new), not in a
public issue. You'll get a reply within a few days.

## Supported versions

| Version | Supported |
|---|---|
| 1.0 (latest release) | ✅ |

## What the app does to stay safe

- HTTPS only, trusting system certificate authorities only; cleartext traffic is refused.
- Scraped article HTML is shown in a WebView with JavaScript, file access, content access and mixed content
  disabled; links open only as `http(s)` in apps that declare themselves browsers.
- Strict intent matching; every component except the launcher is unexported.
- The share-card provider serves one file, read-only, only to the app it was shared with.
- No data in Android backups or device transfers.
- Release builds are obfuscated and signed with a key kept outside the repository.
- No secrets in the repository: the Firebase config and signing material live only on the build machine.
