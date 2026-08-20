# Scope

A matching engine is a deterministic function from an ordered stream of commands for one
instrument to an ordered stream of events sufficient to rebuild its book.

Everything in this repository follows from that sentence, including the parts it refuses to do.
This engine is built to be a component of an exchange, so the boundary matters more than the
feature list. The components either side of it are a separate project.

## Where the engine sits

A member firm speaks a session protocol. A gateway terminates that session and owns the socket,
authentication, heartbeats, framing and protocol decode, and converts a client message into a
canonical internal command. Pre-trade risk checks size, price collars and credit. A sequencer takes
commands from many gateways, imposes one total order and journals the result.

The engine consumes that sequenced stream and emits events. A market data publisher turns those
events into the public feed. Drop copy and clearing consume the same events elsewhere.

The engine never touches the network. Its input arrives already sequenced, already risk checked and
already decoded from any session protocol.

## What the engine owns

- the book and its priority rule
- order lifecycle: new, cancel, replace
- pricing instruction, time in force and liquidity flags
- instrument level validation: tick, lot, price band, field consistency
- self match prevention
- execution ids and the numbering of its own output
- session state, if auctions are ever added

## What the engine does not own

- sessions, authentication, framing, heartbeats
- throttling and rate limits
- pre-trade risk and credit
- input sequencing
- market data publication, snapshots and conflation
- clearing and settlement
- journaling and recovery orchestration
- mapping order ids back to client sessions

Recovery is worth a line because its absence looks like a gap. A deterministic engine plus the
upstream journal is the recovery mechanism: replaying the log reproduces the book exactly. The
engine earns recovery by being a pure function of its input, so there is nothing further for it to
implement.

## One instrument per engine

An engine instance handles one instrument. Many instruments means many instances, partitioned
across threads by whatever runs above. That is what keeps the single writer rule true without
locks, and it is why concurrency is not in the list above.
