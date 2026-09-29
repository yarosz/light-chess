# Chess privacy

Approved by the maintainer, 2026-09-28. It covers Chess from v3 on, once Games with a friend are
switched on; until then Chess never uses the network, and nothing leaves the phone.

Chess has no accounts and asks for no name, email or phone number.

**Puzzles and Games against the computer** happen entirely on your phone. Nothing about them is
ever sent anywhere.

**Games with a friend** pass through a small relay server that the developer runs on Cloudflare
(`relay/` in this repository).

- For each Game, the relay stores the Moves, its own timestamps, and which side each phone plays.
- Each phone proves its seat with a secret that only the phone holds. The relay keeps only a scrambled
  (hashed) copy of that secret.
- You can give your opponent a name in Chess, like "Dad". That name is saved only on your phone and
  is never sent to the relay or to your friend.
- Invite codes work once and expire after 48 hours.
- The relay deletes each Game within 30 days of the last thing either phone sent it.
- The relay keeps no request logs. It reads your IP address for a moment, to limit how fast invite
  codes can be tried, and never stores it. As with any internet service, Cloudflare carries the
  traffic and handles IP addresses under its own privacy policy.

Nothing is sold, shared, or used for ads, analytics or tracking. Chess contacts the relay only when
you have a Game with a friend, or when you create or enter a code. The relay's code is open source in
this repository.

Questions: open an issue at https://github.com/yarosz/light-chess/issues.
