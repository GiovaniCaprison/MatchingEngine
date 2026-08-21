# Scope

A matching engine is a deterministic function from an ordered stream of commands for one instrument
to an ordered stream of events sufficient to rebuild its book.

The engine is built to be a component of an exchange, so its boundary matters more than its feature
list. The components either side of it are a separate project.

## Where the engine sits

A member firm speaks a session protocol. A gateway terminates that session and owns the socket,
authentication, heartbeats, framing and protocol decode, and converts a client message into a
canonical internal command. Pre-trade risk checks size, price collars and credit. A sequencer takes
commands from many gateways, imposes one total order and journals the result.

The engine consumes that sequenced stream and emits events. A market data publisher turns those
events into the public feed. Drop copy and clearing consume the same events elsewhere.

The engine never touches the network. Its input arrives already sequenced, already risk checked and
already decoded from any session protocol.

## Two structures

The engine owns a limit order book and a trigger book.

The limit order book holds resting limit orders, including the displayed and hidden portions of
iceberg orders. Market, immediate-or-cancel and fill-or-kill orders cross it without joining it.

The trigger book holds stop and stop-limit orders, keyed by trigger price. A resting stop is not
liquidity and is invisible to the book: it is a condition evaluated against the last executed price,
and on firing it becomes an ordinary order which then enters or crosses the limit order book.

## What the engine owns

- the limit order book, and the allocation algorithm configured for the instrument
- the trigger book, and the evaluation of triggers against executed prices
- order lifecycle: new, cancel, replace, mass cancel
- pricing instruction, time in force, liquidity flags, minimum quantity, hidden quantity
- validation of an incoming order against the instrument: tick, lot, static and dynamic price bands,
  field consistency
- self match prevention
- trading state, including auction call phases and uncrossing
- execution ids and the numbering of its own output

## Not a matching engine's job anywhere

- sessions, authentication, framing, heartbeats
- throttling and rate limits
- pre-trade risk and credit
- input sequencing
- market data publication, snapshots and conflation
- clearing and settlement
- journaling and recovery orchestration
- mapping order ids back to client sessions
- validating instrument reference data. The definition command is trusted, and whatever owns
  reference data checks it
- expiry of good-till-date orders, which needs a calendar the engine does not have. The scheduler
  cancels them and the engine sees an ordinary cancel

Recovery is listed here because its absence reads as an omission. A deterministic engine plus the
upstream journal is the recovery mechanism: replaying the log reproduces the book exactly, so there
is nothing further for the engine to implement.

## A different kind of engine

These are matching, and a venue that is good at them runs a purpose-built engine rather than
extending this one.

- implied and spread matching, where orders in one book are derived from another. CME runs a separate
  implied engine for exactly this
- midpoint and dark books, which match without a visible book
- periodic auction books, where the whole venue is a repeated auction rather than continuous trading
- request for quote, which is negotiation rather than a book

## One instrument per engine

An engine instance handles one instrument, with one allocation algorithm fixed for its lifetime.
Many instruments means many instances, partitioned across threads by whatever runs above. That is
what keeps the single writer rule true without locks, and it is why concurrency is not in the list
above.
