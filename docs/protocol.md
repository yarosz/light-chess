# Relay protocol, v1.0

The contract between the Chess Tool and the Relay (ADR 0002). The Relay keeps each Correspondence
Game's Game Events in order, stamps them with its own time, and passes them between the two Seats.
It has no chess logic: it never checks a Move, never reads a Position and never records a Result.
Each phone checks every Game Event with its own rules core and derives the Result itself.

The implementation is `relay/` (a Cloudflare Worker with one SQLite-backed Durable Object per
Game). The Kotlin client implements this document. Decision-log ids are cited in brackets.

## Conventions

- HTTPS only. A request over plain HTTP, to any endpoint, gets `426 upgrade_required` before any
  other check or rate limit. It is never redirected: a redirected POST can arrive as a GET, and a
  secret sent in the clear has already been sent [L3]. The one exception is a local `wrangler dev`
  reached as `localhost`, `127.0.0.1`, `[::1]` or `10.0.2.2` (the Android emulator's alias for the
  host); the deployed Relay answers at none of those [W11].
  Request and response bodies are JSON (`Content-Type: application/json`).
- A request body is at most 4,096 bytes. A larger one gets `413 body_too_large`. The Relay checks
  `Content-Length` and also counts the bytes it reads, so a chunked body is capped too [R5].
- Times are integers: milliseconds since the Unix epoch, from the Relay's clock (`serverTime`).
  Timeouts are computed from these stamps, never from a phone's clock [C2].
- A side is `"white"` or `"black"`.
- A **seat secret** is 32 random bytes in unpadded base64url (43 characters). The creator's is made
  by the Relay and returned once, in the create response. The joining Seat's is chosen by the phone
  and sent with the redeem or join, so a retry after a lost response takes the same Seat again [W9].
  The phone keeps it out of backup paths [C3, F10]. The Relay stores only its SHA-256. There is no
  seat recovery [E6]. A request that acts as a Seat sends `Authorization: Bearer <seat secret>`.
- A **digest** (`hash`) is the rules core's `Position.digest`: SHA-256, lowercase hex, of the first
  four fields of the canonical FEN (en passant square only when a legal en passant capture exists,
  castling in KQkq order) [C4, contradiction 8]. The Relay checks only that it is 64 lowercase hex
  characters.
- The Relay logs nothing about requests (Workers Logs off) [C6]. It stores no names, no chat and no
  ratings.

## Versions [E9]

- The protocol version is `major.minor`, written `v` (for example `"1.0"`). Minor versions only add
  optional fields, kinds or endpoints; a client ignores fields it does not know.
- The path carries the major: every endpoint except `/health` lives under `/v<major>/`.
- A Game is pinned to the `v` it was created with. Every append sends `v`, and its major must be the
  Game's major.
- A request to a major the Relay doesn't serve, or whose body `v` disagrees with the path's major,
  gets `400 unsupported_version`. A request whose major is served but isn't the Game's pinned major
  gets `409 version_mismatch` with the Game's `v`. Either way the phone shows "Update Chess to
  continue this game".
- The pin is stored with every Game now, and appends check it now. On `GET events`, `/sync`,
  `/join` and `/live` the `version_mismatch` check is enforced from the second major: while the
  Relay serves one major, a request to a served major can't disagree with a Game's pin [R6].
- The phone handles `400 unsupported_version` and `409 version_mismatch` on every endpoint, not
  only on appends [R6].
- The Relay keeps serving every major pinned by a live Game, and `/health` lists them.

## Errors

Every error has one shape:

```json
{ "error": { "code": "seq_conflict", "message": "Entry 7 already exists; fetch events since 6" } }
```

`message` is for logs and debugging; phones branch on `code` only. Some errors add fields next to
`code` (listed with the error).

| Status | `code` | Meaning |
|---|---|---|
| 400 | `bad_request` | The body, query or path is malformed. |
| 400 | `unsupported_version` | The Relay doesn't serve this major, or `v` disagrees with the path. Adds `majors`. |
| 401 | `bad_seat_secret` | No bearer seat secret, or it belongs to neither Seat of this Game. |
| 403 | `not_your_turn` | The ply parity says the other side moves (or claims, or offers a draw, or answers a rematch). |
| 404 | `not_found` | No such endpoint. |
| 404 | `game_not_found` | No such Game: never created, cancelled, expired or deleted. |
| 404 | `invite_not_found` | No such Invite Code, or it expired, or it was cancelled. |
| 405 | `method_not_allowed` | The endpoint exists but not with this method. |
| 409 | `version_mismatch` | The Game is pinned to another major. Adds `gameV`. |
| 409 | `invite_used` | The Invite Code or join token was already redeemed. |
| 409 | `invite_redeemed` | Cancel came too late: the other Seat is already taken. |
| 409 | `game_not_started` | Nobody has redeemed the invite yet. |
| 409 | `seq_conflict` | `seq` is not the next entry, and no stored entry matches. Adds `latestSeq`. |
| 409 | `game_closed` | The log is closed to this kind (see "After the end"). |
| 409 | `rematch_already_offered` | A Game gets one rematch offer. |
| 409 | `no_rematch_offer` | Nothing to accept or decline. |
| 409 | `rematch_answered` | The rematch offer has already been answered. |
| 409 | `claim_too_early` | The time for the Move has not run out. Adds `deadline`. |
| 409 | `draw_already_offered` | The sender's own draw offer is still open. |
| 409 | `no_draw_offer` | No open draw offer from the other Seat to accept or decline. |
| 409 | `game_not_over` | A `rematchOffer` on an open log. |
| 409 | `log_full` | The Game already holds 2,000 entries (only `move`, `drawOffer` and `drawDecline`). |
| 413 | `body_too_large` | The request body is over 4,096 bytes. |
| 426 | `upgrade_required` | The request came over plain HTTP (use HTTPS), or the live endpoint needs a WebSocket upgrade. |
| 429 | `rate_limited` | Too many invite redemptions or Games created from this address. Sends `Retry-After`. |

## The entry

A Game is its list of entries, in order. Each entry is one Game Event:

```json
{
  "v": "1.0",
  "gameId": "5f0c…",
  "seq": 3,
  "ply": 2,
  "side": "black",
  "kind": "move",
  "uci": "e7e5",
  "hash": "9c1b…",
  "serverTime": 1790000000000
}
```

| Field | Set by | Meaning |
|---|---|---|
| `v` | phone | The sender's protocol version. |
| `gameId` | Relay | The Game. |
| `seq` | phone, checked | The entry's place in the log, from 1. The compare-and-swap key and the sync cursor. |
| `ply` | phone, checked | The number of Moves played once this entry is applied. A `move` adds one; every other kind repeats the previous entry's `ply` (0 before the first Move). |
| `side` | Relay | The Seat that appended it, from the seat secret. |
| `kind` | phone | See below. |
| `uci` | phone | The Move in UCI (`e2e4`, `e7e8q`). Present only for `move`. |
| `end` | phone | Only on a `move`, and only `true`: the mover's rules core found that this Move ends the Game (checkmate, stalemate, threefold repetition, the 50-move rule or a dead position). Closes the log. Absent otherwise [R2]. |
| `hash` | phone | The digest of the Position after this entry. For every kind but `move` it is the digest of the current Position. |
| `rematch` | phone | Only on `rematchOffer`: `{ "gameId", "joinToken" }` of the new Game. |
| `serverTime` | Relay | When the Relay stored it. |

Kinds [C4, E8, contradiction 9]:

| `kind` | Meaning | Who may append |
|---|---|---|
| `move` | One Move. With `end: true`, it also ends the Game and closes the log. | The side to move: odd `ply` is White (ply 1 is White's first Move), even is Black. |
| `resign` | The sender resigns. Closes the log. | Either Seat. |
| `drawOffer` | The sender offers a draw, FIDE style: sent after the sender's own Move. | The side not to move (it has just moved), from ply 1, when no offer is open. |
| `drawAccept` | The sender accepts the open draw offer. Closes the log. | The side to move, when the other side's offer is open. |
| `drawDecline` | The sender refuses the open draw offer. (A Move also refuses it implicitly.) | The side to move, when the other side's offer is open. |
| `claim` | A timeout claim: the side to move let its time run out. Closes the log. | The side not to move, once the deadline has passed. |
| `rematchOffer` | Offers a rematch in a new Game. | Either Seat, once per Game, once the log is closed. |
| `rematchAccept` | Accepts the rematch offer. | The Seat that did not offer. |
| `rematchDecline` | Declines the rematch offer. | The Seat that did not offer. |

There is no takeback kind: a Correspondence Game has no takeback [contradiction 6]. `claim` is a
timeout claim only; threefold repetition and the 50-move rule are automatic draws that each phone
derives, so no one claims them [contradiction 9].

The Relay checks only what it can without chess: the seat secret, `seq`, `ply` arithmetic, ply
parity for `move`, `claim` and the draw kinds, the timeout deadline for `claim`, the draw-offer and
rematch bookkeeping, and the field formats. Whether a Move is legal, and whether a Move really
ends the Game as its `end` flag says, are for the phones. A phone that finds an entry its rules
core refuses, a `hash` that doesn't match its own digest, or an `end` flag that doesn't match the
Result its rules core derives, freezes the Game as "Out of sync" [C5].

### Draw offers [R1]

The Relay tracks the open draw offer from the kinds alone, as `openDrawBy` (`null`, `"white"` or
`"black"`, returned by `GET events`):

- `drawOffer` comes from the side not to move, which has just moved ("offer with your move",
  G3), once at least one Move has been played (`ply` 1 or more). From the side to move, or at
  `ply` 0, it gets `403 not_your_turn`. While the sender's offer is still open it gets
  `409 draw_already_offered`. It sets `openDrawBy` to the sender's side.
- `drawAccept` and `drawDecline` come from the side to move, and only while the other side's offer
  is open. Anything else gets `409 no_draw_offer`.
- A `move` or a `drawDecline` clears `openDrawBy`. A `drawAccept` closes the log, and a closed log
  has no open offer, so `openDrawBy` is `null` on every closed log.

### The timeout deadline [C2]

`deadline = T + daysPerMove × 86,400,000`, where `T` is the `serverTime` of the latest `move` entry,
or the Game's `startedAt` before the first Move. Draw offers and their answers don't reset it. A
`claim` before the deadline gets `409 claim_too_early` with `deadline`. The Relay checks this only
so that it never closes a log on a claim both phones would refuse; the phones still derive the
Result. `GET events` returns the side to move's `deadline`, so both phones show the same time
left.

Late Moves are accepted [H3, R3]: a Game is lost on time only through a `claim`. The claim window
opens at the deadline and closes as soon as a `move` from the late side is stored; the other
side's clock then runs from that Move's `serverTime`. After the deadline, `resign`, `drawAccept`,
`drawDecline` and a late `move` with `end` are all still accepted.

### After the end [C5, E8]

The log closes at the first `resign`, `drawAccept`, `claim`, or `move` with `end: true` [R2].
A closed log accepts only the rematch kinds:

1. One `rematchOffer`, by either Seat. A second gets `rematch_already_offered`.
2. Then one `rematchAccept` or `rematchDecline`, by the other Seat (the offering Seat gets
   `not_your_turn`). A second answer gets `rematch_answered`; an answer with no offer gets
   `no_rematch_offer`.
3. After the answer the log takes nothing more: another offer gets `rematch_already_offered`,
   another answer `rematch_answered`, and any other kind `game_closed`.

Any other kind on a closed log gets `game_closed`. A `rematchOffer` on an open log gets
`409 game_not_over` [R2]. The Relay cannot see a checkmate, a stalemate or an automatic draw, so
the Move that ends the Game carries `end: true`, set by the mover's rules core, and that closes the
log before any rematch.

### Appending

`POST /v1/games/{gameId}/events`, with the bearer seat secret:

```json
{ "v": "1.0", "seq": 3, "ply": 2, "kind": "move", "uci": "e7e5", "hash": "9c1b…" }
```

- Compare-and-swap: `seq` must be `latestSeq + 1`. `ply` must be the previous entry's `ply`
  plus one for a `move`, and equal to it otherwise; a wrong `ply` with the right `seq` is a
  `bad_request`.
- Idempotent re-append: If `seq` names an entry that already exists and that entry has the
  same `side`, `kind`, `ply`, `uci`, `end`, `hash` and `rematch`, the Relay returns it unchanged with
  `200` (its original `serverTime`). A retry after a lost response is therefore safe [C8].
- Otherwise a taken or skipped `seq` gets `409 seq_conflict` with `latestSeq`: fetch the events
  since your last `seq`, replay them, and decide again. The pending entry is rolled back if it no
  longer makes sense [C8].
- A new entry returns `201 { "entry": {…} }`; a matching retry returns `200 { "entry": {…} }`.
- The entry cap [R4]: once the log holds 2,000 entries, a `move`, `drawOffer` or `drawDecline`
  gets `409 log_full`. `resign`, `drawAccept`, `claim` and the rematch kinds are exempt, so a full
  Game can still end and be followed by a rematch; each of them can appear only a few times, since
  the first three close the log. The Relay has no N-ply draw rule.

## Endpoints

| Method and path | Auth | Purpose |
|---|---|---|
| `GET /health` | none | Liveness and the served majors. |
| `POST /v1/games` | none, rate-limited | Create a Game and its invite. The Relay makes the creator's seat secret. |
| `POST /v1/invites/{code}/redeem` | none, rate-limited | Take the other Seat with an Invite Code and the phone's own seat secret. |
| `POST /v1/games/{gameId}/join` | none | Take the other Seat with a rematch join token and the phone's own seat secret. |
| `DELETE /v1/games/{gameId}/invite` | creator's seat | Cancel an unredeemed invite. |
| `GET /v1/games/{gameId}/events?since={seq}` | seat | The Game and its entries after `seq`. |
| `POST /v1/games/{gameId}/events` | seat | Append one entry. |
| `POST /v1/sync` | a seat per Game | Up to 5 Games' events at once. |
| `GET /v1/games/{gameId}/live` | seat | WebSocket for presence and pushed entries. |

### `GET /health`

`200 { "status": "ok", "protocol": "1.0", "majors": [1] }`. `majors` lists every major the Relay
serves [E9, C7].

### `POST /v1/games`: create

```json
{ "v": "1.0", "side": "white", "daysPerMove": 3, "invite": "code" }
```

- `side`: the creator's side, fixed for the Game [C3]. The other Seat gets the other side.
- `daysPerMove`: `1`, `3` or `7` [C2]. The Relay uses it only for the `claim` deadline.
- `invite`: `"code"` (default) for an Invite Code passed by hand, or `"token"` for a rematch join
  token passed through the old Game's log.

`201`:

```json
{
  "v": "1.0", "gameId": "5f0c…", "side": "white", "daysPerMove": 3,
  "seatSecret": "q3…", "inviteCode": "ABCD-EFGH", "inviteExpiresAt": 1790172800000,
  "serverTime": 1790000000000
}
```

With `"invite": "token"`, `joinToken` (43 base64url characters) replaces `inviteCode`.

**Invite Codes** [C3]: 8 characters of Crockford base32 (`0-9 A-Z` without `I L O U`), 40 random
bits, shown as `ABCD-EFGH`. The Relay reads a code case-insensitively, ignores `-` and spaces, and
reads `O` as `0` and `I`/`L` as `1`. A code works once and expires 48 hours after creation. A Game
whose invite expires unredeemed is deleted, and the code may later be issued again.

A lost create response leaves an invite nobody holds; it expires unredeemed after 48 hours [W9].

The Relay has no accounts, so the cap of 5 Games (outstanding invites and outgoing rematch offers
included) is the phone's to enforce [E7, F9].

Rate limit [L2]: creates are limited to 10 per 60 seconds per client address, keyed as for
redemptions below, and counted before the body is read. Past it: `429 rate_limited` with
`Retry-After: 60`. A phone creates one Game per invite or rematch offer and holds at most 5, so
10 a minute leaves room for all 5 at once plus a retry of each after lost responses.

### `POST /v1/invites/{code}/redeem` and `POST /v1/games/{gameId}/join`: redeem

Body `{ "v": "1.0", "seatSecret": "…" }` for a code; `{ "v": "1.0", "seatSecret": "…", "joinToken":
"…" }` for a join token. `seatSecret` is the joining Seat's secret: 32 random bytes the phone chose,
in unpadded base64url (43 characters), written to its own file before the request is sent [W9].

`200`:

```json
{
  "v": "1.0", "gameId": "5f0c…", "side": "black", "daysPerMove": 3,
  "startedAt": 1790000500000, "serverTime": 1790000500000
}
```

`v` is the Game's pinned version. `startedAt` starts White's clock. The secret is not returned: the
phone has it.

- Idempotent retry [W9]: the same code or join token with the same `seatSecret` answers `200` again
  with the same Seat and `startedAt`, however far the Game has gone on since. That is how a phone
  recovers a lost response. The same code or token with another secret gets `409 invite_used`.
- A `seatSecret` missing or not 43 base64url characters, or equal to the creator's own, gets
  `400 bad_request`.

- An unknown, expired or cancelled code: `404 invite_not_found`. A used one: `409 invite_used`.
- A wrong join token, or a Game that no longer exists: `404 invite_not_found`. A used one:
  `409 invite_used`. A malformed `gameId`: `404 game_not_found`. A `joinToken` that isn't 43
  base64url characters: `400 bad_request` (so an Invite Code can't be redeemed here, around the
  rate limit).
- Rate limit [F11]: redemptions by code are limited to 10 per 60 seconds per client address,
  counted before the code is read. Past it: `429 rate_limited` with `Retry-After: 60`. Join tokens
  carry 256 bits and are not limited.
- The client address [L1] is `CF-Connecting-IP`: an IPv4 address whole, an IPv6 address by its /64
  (a subscriber is usually handed a whole /64, so keying on the full address would give one client
  2^64 budgets). An IPv4-mapped IPv6 address (`::ffff:1.2.3.4`) counts as its IPv4 address. A
  missing or unreadable header puts the request under one key shared by all such requests. Both
  limits (this one and create's) key the same way. Cloudflare's rate-limiting binding is per
  location and eventually consistent, so the limits are approximate.

### `DELETE /v1/games/{gameId}/invite`: cancel [G2]

With the creator's seat secret. Cancelling and redeeming are serialised by the Game's Durable
Object, so exactly one of them wins:

- Still open: `200 { "cancelled": true, "gameId": "…" }`, and the Game is deleted. Only now does
  the phone free the slot.
- Already redeemed: `409 invite_redeemed`. The phone turns the invite row into the Game.
- Already expired or cancelled: `404 game_not_found`. The phone drops the row.
- The joining Seat's secret, or any other: `401 bad_seat_secret`.

### `GET /v1/games/{gameId}/events?since={seq}`: read

`since` defaults to 0. `200`:

```json
{
  "v": "1.0", "gameId": "5f0c…", "status": "active", "side": "white", "daysPerMove": 3,
  "createdAt": 1790000000000, "startedAt": 1790000500000, "inviteExpiresAt": null,
  "latestSeq": 3, "latestPly": 2, "openDrawBy": null, "deadline": 1790259700000,
  "rematch": null, "serverTime": 1790001000000,
  "entries": [ … entries with seq > since, in order … ]
}
```

- `status`: `"waiting"` until the invite is redeemed (the creator can poll this), `"active"`, or
  `"closed"` once the log has closed.
- `side`: the caller's own Seat.
- `openDrawBy`: `null`, or the side whose draw offer is open (see "Draw offers") [R1].
- `deadline`: the `serverTime` at which the side to move's time runs out (see "The timeout
  deadline"), or `null` while `waiting` and once `closed`.
- `rematch`: `null`, or `{ "offeredBy": "white", "gameId": "…", "answer": null | "accept" | "decline" }`.

### `POST /v1/sync`: batched read [E7]

```json
{ "v": "1.0", "games": [ { "gameId": "…", "seatSecret": "…", "since": 3 } ] }
```

One to 5 Games, each with its own seat secret (a batch spans Games with different secrets, so they
travel in the body). `200`:

```json
{ "results": [
  { "gameId": "…", "ok": true, "game": { …the same object as GET events… } },
  { "gameId": "…", "ok": false, "error": { "code": "game_not_found", "message": "…" } }
] }
```

Results come in request order. One Game's error doesn't fail the batch. More than 5 Games, or a
`seatSecret` that isn't 43 base64url characters, is a `400 bad_request` for the whole batch.

### `GET /v1/games/{gameId}/live`: WebSocket [C1, G3]

A WebSocket upgrade with the bearer seat secret in `Authorization` (OkHttp sets it on the upgrade
request). Allowed once the Game has started. A Seat has at most one live socket: a new one closes
the old one with code `4001`.

The socket is for presence and pushed entries only. Appends still go through
`POST /events`, so HTTPS stays the single write path.

- The phone sends `{ "type": "ping" }` every 5 seconds while the Game is on screen.
- The Relay answers each ping, and also tells both sockets whenever a socket opens or closes:

  ```json
  { "type": "presence", "live": true, "opponent": "here", "serverTime": 1790001000000 }
  ```

  A Seat is **here** while it has an open socket that the Relay has heard from (open or ping)
  within the last 10 seconds. `opponent` is `"here"` or `"gone"`. `live` is true only when both
  Seats are here. The phone shows "Live · Your move" only on `live: true`, and drops "Live" when
  the Relay reports the opponent gone, that is after 10 seconds without hearing from it.
- After every append, the Relay pushes `{ "type": "entry", "entry": {…} }` to both sockets. A
  phone that misses a push catches up with `GET /events`.
- A message is at most 256 bytes [R5]. A larger one, even a ping, gets
  `{ "type": "error", "code": "bad_request" }`.
- Any other message gets `{ "type": "error", "code": "bad_request" }`.
- When the Game is deleted, the Relay closes its sockets with code `4004`.

The Durable Object uses the WebSocket Hibernation API, so an idle socket costs no duration.

## Retention [C6]

- An invite unredeemed after 48 hours: the Game is deleted.
- A started Game is deleted 30 days after its last entry (or 30 days after it started, if it has
  none). Each append moves the date. A Durable Object alarm does the deleting; after it, every
  request about the Game gets `game_not_found`.
- A cancelled invite deletes its Game at once.

## What the phone does with this

Not part of the wire format, but it is why the format looks the way it does [C2, C8]:

- Write the pending entry to its file first, show "Not sent" until it is stored, and send it from a
  `LightWork` job with Retry. One pending entry per Game.
- Write a redeem's or join's chosen seat secret to its file before sending, and send the same secret
  again until the Relay answers, so a lost response never loses the Seat [W9].
- Poll `/v1/sync` every hour only while waiting on the opponent [W7].
- Keep every Game Event, check each with the rules core, compare `hash` with its own digest after
  each one, and derive the Result, timeouts included, from `serverTime`.
- Set `end: true` on the Move whose Position the rules core finds ends the Game. An `end` flag,
  sent or received, that doesn't match the rules core's own derived Result freezes the Game as
  "Out of sync" [C5].
- A timeout claim is a one-tap "Claim win on time" button, shown once `deadline` has passed. The
  phone never claims by itself.
- Show time left from `deadline` and `serverTime`, so both phones agree.
- Handle `400 unsupported_version` and `409 version_mismatch` on every endpoint [R6].
