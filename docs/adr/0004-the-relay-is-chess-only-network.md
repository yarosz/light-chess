# The Relay is Chess's only network, and only for Correspondence Games

Supersedes ADR 0003's sentence "v1 declares no INTERNET permission" and its privacy sentence ("Nothing
leaves the phone" is the whole privacy story), from v3 on. The rest of ADR 0003, the committed Pack
and why, stands.

Playing a friend needs a network: two phones pass each Correspondence Game's Game Events through the
Relay (ADR 0002). Chess therefore declares INTERNET in `tool/lighttool.toml`, and holds itself to
three rules (decision log W1):

1. It talks to one host, the Relay at `RelayConfig.URL`, over HTTPS (and, from v3 PR 3, WSS) only.
   Release builds allow no cleartext; a debug build may reach a local Relay on the emulator's host.
2. It sends a request only while the phone holds a Correspondence Game, an open invite or a Seat
   being taken, or when the user creates or redeems a code. With none of these it sends nothing and
   schedules no background job; the hourly job runs only while some Game waits on the opponent.
3. It sends no names, no accounts and no telemetry. The Relay keeps no IP logs and deletes each Game
   within 30 days of the last thing either phone sent it (C6, H8). Puzzles and Games against the
   computer never touch the network.

`RelayConfig.URL` is committed empty until the maintainer deploys the Relay. While it is empty,
Play a friend doesn't exist in the Tool (no Menu entry, no client, no job), About keeps "Chess never
uses the network. Nothing leaves this phone.", and `scripts/release-check.sh relay` refuses to call
a build releasable. (2026-09-29: the URL is set to `https://chess-relay.yarosz.com`, the Relay's
one public address, ahead of its deploy; decision log W11.)

## Considered Options

- Keep INTERNET undeclared: Light's SDK libraries merge INTERNET into every Tool's manifest anyway,
  so it would work today, but the Tool would then use a permission it says it doesn't have.
- A permission only in the builds that have a Relay URL: `lighttool.toml` is committed and Light
  builds from the public commit, so the declared set can't depend on a deploy; the release check
  holds the pair together instead.

## Consequences

- The privacy line changes once the Relay URL is set (UiCopy.PRIVACY_FRIENDS, README, SECURITY.md);
  the maintainer approves its final wording and asks Light whether a Tool that talks to its own
  server needs a privacy statement.
- `scripts/release-check.sh apk` pins INTERNET as declared, and `run` still checks that a release
  with no Relay URL makes no traffic at all.
