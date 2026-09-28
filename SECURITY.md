# Security

Please report vulnerabilities privately through GitHub: **Security → Report a vulnerability** on this
repository. Don't open a public issue for security problems.

Chess v1 never uses the network. The Puzzles ship inside the Tool (ADR 0003), and the Player Rating,
the history and Missed stay in one file in the Tool's own storage. There are no accounts, no
telemetry and no server.

Chess declares no permissions of its own (`tool/lighttool.toml`, decision D5). Light's SDK, which
every Tool is built on, merges some into the package: INTERNET, ACCESS_NETWORK_STATE, CAMERA,
VIBRATE, WAKE_LOCK, FOREGROUND_SERVICE and RECEIVE_BOOT_COMPLETED. `scripts/release-check.sh apk`
fails if a release gains any other. Reports of Chess sending or receiving anything over a network are
especially welcome.
