# Protocol

The engine's whole interface: commands in, events out, as fixed layout binary messages. The
authoritative layout is the SBE schema in `schema/`; this document says what the messages mean.
`REQUIREMENTS.md` says what the engine does with them.

SBE is the FIX Trading Community's binary encoding standard, used by CME iLink among others. One
schema generates both Java and C++ codecs, so a cross-language comparison is fed identical bytes.
Fields sit at fixed offsets, so an implementation may read them in place or copy them into objects,
and that choice is one of the things being measured.

This is an internal format, not a client facing one. A client protocol carries hundreds of fields,
optional groups and session plumbing. A canonical command is a dozen fields wide, which is why a
gateway's decode is expensive and the engine's is nearly free.

## Sequencing

Every command carries the sequence number assigned upstream. The engine never generates an input
sequence of its own.

Every event carries its own output sequence and nothing else about its cause. Events are published as
they are produced, with no lookahead and no batching, so the engine never holds one back to learn what
follows it.

Nothing groups events by the command that produced them, because nothing needs to. Every event leaves
the visible book in a valid state, so a consumer applies them one at a time. ITCH works this way. CME
carries a match event indicator because its consumers compute implied prices and statistics that have
to be applied atomically, and neither is in scope here.

## Commands

All commands carry a header of message type, schema version, instrument id and input sequence.

`InstrumentDefinition` configures the instrument for the life of the engine: tick size, lot size,
price scale, static price bounds, dynamic band width, opening reference price, and allocation
algorithm. It precedes every other command. Sending it as a message rather than configuring each
implementation out of band is what stops a Java run and a C++ run being configured differently.

`NewOrder` carries client order id, participant id, side, pricing instruction, time in force, a flags
word, price and quantity, then four optional qualifiers: minimum quantity, display quantity, trigger
price and self match id. Zero means absent for all four.

`CancelOrder` carries the participant id and the client order id of the order to cancel.

`ReplaceOrder` carries the same pair, and the order's whole intended quantity and price. Whole rather
than remaining: a client asking for sixty shares means sixty, and the engine works out what is left by
subtracting what has traded.

`MassCancel` carries client order id and the participant id whose resting orders are to be removed.

`SessionStateChange` carries the state to enter. The engine has no clock, so every transition arrives
this way, put here by whatever schedules the venue.

## Two identities

An order has two, and they are not interchangeable. The client's own id names it in commands. The
engine's id names it in events.

A command can only carry an id its sender already has, and a client learns the engine's id by being
told, so a cancel that named it could not be sent until a round trip had completed. Naming the client's
id instead means a client can cancel an order the moment it has decided to, and it means a recorded
command stream is replayable without knowing what any engine did with it.

The engine's id goes the other way. Events are what a market data feed is built from, and a feed cannot
carry one participant's private numbering, so every event names the order by the id the engine gave it.
Nasdaq splits it the same way: OUCH cancels a token the client chose, and the ITCH feed carries the
exchange's order reference number.

Client order ids are unique per participant for the life of the session. That is the sender's
responsibility, not something the engine checks (P-14).

## How an order's kind is read from its fields

There is no order type field. An order's behaviour is the combination of its fields, which is what
keeps the walk free of type dispatch.

Pricing instruction and time in force are independent axes: `LIMIT` or `MARKET` for the first,
`GOOD_TILL_CANCEL`, `DAY`, `IMMEDIATE_OR_CANCEL` or `FILL_OR_KILL` for the second. The flags word
carries post-only.

A non-zero trigger price makes the order a stop. Combined with `LIMIT` it is a stop-limit, and with
`MARKET` a stop-market.

A display quantity below the order quantity makes it an iceberg. Equal or absent means fully
displayed.

A non-zero self match id opts the order into self match prevention against other orders carrying the
same value.

## Events

`OrderAccepted` reports the assigned engine order id. It is emitted for a stop as well as for a book
order; a stop produces no resting event, since it is not in the book.

`OrderRejected` reports a machine readable reason and means no state changed.

`OrderRested` reports side, price and displayed quantity for an order that has entered the book. This
is the add-order a market data feed needs, and it carries displayed quantity only.

`OrderExecuted` reports execution id, both order ids, price and quantity. In continuous trading the
price is the resting order's and the aggressor has not rested. In an auction neither side aggressed and
the price is the uncrossing price.

A consumer decrements whichever of the two orders it is holding, which is one rule for both: in
continuous trading that is the resting side alone, and in an auction it is both. A consumer that only
ever followed the side named resting would keep a filled order for the rest of the session.

`OrderReduced` reports a new displayed quantity for an order that kept its queue position.

`OrderRemoved` reports the quantity removed and why: cancelled, replaced, mass cancelled, an
immediate-or-cancel remainder, or self match prevention.

`OrderTriggered` reports that a stop's condition was met and it has left the trigger book. The order
it became then produces its own events.

`SessionStateChanged` reports the state now in effect.

`AuctionIndicative` reports the uncrossing price and volume that would result if the auction ran now.

## Three conventions taken from ITCH

A replace that loses queue position is reported as `OrderRemoved` then `OrderRested`, so the layer
above needs no special handling for replace. The order keeps its engine id across both, since
`ReplaceOrder` names an order by that id and no event carries a new one.

A resting order that is fully executed gets no removal event; a consumer tracking quantity sees it
reach zero. Recorded here because the other reading is that an event is missing.

Hidden quantity is never reported, and it is displayed before it trades. An iceberg's tranche executes
to zero, which a consumer tracking quantity has already seen, and the next tranche appears as an
`OrderRested`, indistinguishable from a new order arriving at that price. An auction does the same,
which costs an event pair per tranche and buys the property that no execution ever reports more
quantity against an order than the feed said was there.

Nasdaq does the opposite, and it is worth recording which of the two this is. ITCH reports an execution
against hidden quantity as a trade naming no order at all, so the volume reaches the tape and the
visible book is untouched. That costs a message type and gives up attributing the volume to an order;
revealing first costs the events and keeps every execution attributable. A session's worth of AAPL says
the choice is not marginal: hidden executions were under one percent of messages and twenty eight
percent of the volume that traded. There is no removal between the two, for the
same reason a fully executed order gets none. That is the point of an iceberg, and it means the feed
stays sufficient to rebuild the visible book without revealing what it cannot see.

Between them the events are enough to rebuild the visible book at any point in the stream, which is
the contract with the market data publisher above.
