# Security

Please report vulnerabilities privately through GitHub: **Security → Report a vulnerability** on this
repository. Don't open a public issue for security problems.

Puzzles and Games against the computer never use the network. The Puzzles ship inside the Tool
(ADR 0003), and the Player Rating, the history, Missed and the Games stay in the Tool's own storage.
There are no accounts and no telemetry.

Playing a friend is the one exception (ADR 0004): its Game Events go through the Relay (`relay/`,
`docs/protocol.md`), over HTTPS, with no names or accounts, and each Game's seat secret stays in the
Tool's `no_backup` storage. It exists only in a build with a Relay URL (`RelayConfig.URL`, empty until
the Relay is deployed). Relay issues are in scope too.

Chess declares one permission, INTERNET, for the Relay (`tool/lighttool.toml`). Light's SDK, which
every Tool is built on, merges some into the package: INTERNET, ACCESS_NETWORK_STATE, CAMERA,
VIBRATE, WAKE_LOCK, FOREGROUND_SERVICE and RECEIVE_BOOT_COMPLETED. `scripts/release-check.sh apk`
fails if a release gains any other. Reports of Chess sending or receiving anything over a network are
especially welcome.
