# A Relay that knows no chess

Playing a friend uses Correspondence Games through a Relay: a Cloudflare Worker with one Durable
Object per Game. The Relay keeps each Game's Game Events in order (append only, with
compare-and-swap on the Ply) and stamps each one with its own time, and that is all it does. It has
no chess logic and never records a Result. Both phones hold the full list of Game Events and check
every Move with their own rules core. They derive every Result themselves, including timeouts
computed from the Relay's timestamps. After each Game Event they compare a digest of a canonical
FEN: an en passant square only when a legal en passant capture exists, and castling rights in KQkq
order. A mismatch freezes the Game as "Out of sync" instead of letting one phone decide.

Correspondence comes first because a Tool can't alert its user. The plugin blocks `android.app`,
LightOS shows no notifications from Tools, and remote push is unproven on production LightOS. A
Tool can sync in the background through `LightWork`, but it can't tell anyone. Days per Move
survive screen locks, process death and a phone left in a drawer. Live play is the same Game with
both phones on screen, not a separate mode.

## Considered Options

- Lichess's Board API: Lichess becomes the authority, not the two phones; it needs accounts and OAuth
  on a phone with no browser; and it pairs players with Lichess users, not Light Phone users.
- An authoritative chess server: it duplicates the rules core in a second language and makes the
  server a place where games can be cheated or corrupted.
- SMS or email transport: the SDK can open the dialer but can't send messages.

## Consequences

- The protocol carries a version (`v`) pinned per Game, and the Relay must keep serving every major
  version pinned by a live Game.
- There's no seat recovery: a Seat is a random secret on one phone.
- No chat, no names and no ratings are sent, and a Game is deleted 30 days after its last Game Event.
