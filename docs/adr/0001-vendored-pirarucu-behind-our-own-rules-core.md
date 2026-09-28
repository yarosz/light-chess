---
status: proposed (final once the Karballo spike and the LP3 benchmark are in)
---

# Vendored Pirarucu behind our own rules core

The computer opponent is Pirarucu's `pirarucu-common` (pure Kotlin, GPLv3, about 3050 on CCRL
40/15 with one CPU), vendored as source and flattened to plain Kotlin. It sits behind a narrow
interface: a start Position and Moves go in, a best Move and search info come out, all as FEN and
UCI text. Our own rules core decides what is legal, whether a Puzzle is solved and how a Game ends,
and it checks every Move the engine returns. The engine's fast, mutable board never becomes game
state. So the Tool carries two move generators on purpose. Perft keeps them in agreement, and the
engine can be swapped without touching Games, Puzzles or the Relay protocol.

Strength Levels live in our wrapper: a node limit (the same on every phone) plus a pick among the
best few root Moves, found by re-searching with the best-so-far Move excluded. The vendored
evaluation stays unmodified, so fixed-depth node counts must match upstream exactly.

## Considered Options

- CuckooChess: Java, so it needs a full conversion; its resource loading uses reflection, which the
  plugin blocks; about 470 Elo weaker.
- Stockfish compiled to WebAssembly, run through a Kotlin interpreter: modern builds need SIMD and
  threads, NNUE is very slow when interpreted, and the network files break the 5 MB asset limit.
- Karballo (Kotlin, MIT, about 2730, built-in strength limiter): still being compared. It gets its
  own spike, and v2's first PR picks between the two engines on measurements, not on licence alone.
  It is the choice if Light refuses a GPLv3 Tool or Pirarucu is too slow on the phone.
- Writing our own engine: months of work to reach a fraction of Pirarucu's strength.

## Consequences

- The repo is MIT until v2. Vendoring Pirarucu makes the Tool as distributed GPLv3, while our own
  files stay MIT inside it. So v1 doesn't wait for Light's answer on GPL.

- Pirarucu's upstream has been inactive since 2020, so we own the fork.
- Always pass explicit hash sizes: the defaults allocate 256 MB and 32 MB.
- A search stopped before depth 1 returns no Move. The wrapper needs a fallback Move and a minimum
  search time.
- The phone speed benchmark is the go/no-go gate for this decision. If the phone is too slow, we
  switch to Karballo before building on the engine.
