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

Every event carries two numbers: its own output sequence, and the input sequence of the command that
caused it, so causality is readable from the stream alone.

## Commands

All commands carry a header of message type, schema version, instrument id and input sequence.

`InstrumentDefinition` configures the instrument for the life of the engine: tick size, lot size,
price scale, static price bounds, dynamic band width, opening reference price, and allocation
algorithm. It precedes every other command. Sending it as a message rather than configuring each
implementation out of band is what stops a Java run and a C++ run being configured differently.

`NewOrder` carries client order id, participant id, side, pricing instruction, time in force, a flags
word, price and quantity, then four optional qualifiers: minimum quantity, display quantity, trigger
price and self match id. Zero means absent for all four.

`CancelOrder` carries client order id, participant id and the engine order id to cancel.

`ReplaceOrder` carries client order id, participant id, the engine order id, and the full intended
new quantity and price.

`MassCancel` carries client order id and the participant id whose resting orders are to be removed.

`SessionStateChange` carries the state to enter. The engine has no clock, so every transition arrives
this way, put here by whatever schedules the venue.

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

`OrderExecuted` reports execution id, aggressor order id, resting order id, price and quantity. The
price is the resting order's.

`OrderReduced` reports a new displayed quantity for an order that kept its queue position.

`OrderRemoved` reports the quantity removed and why: cancelled, replaced, mass cancelled, an
immediate-or-cancel remainder, or self match prevention.

`OrderTriggered` reports that a stop's condition was met and it has left the trigger book. The order
it became then produces its own events.

`SessionStateChanged` reports the state now in effect.

`AuctionIndicative` reports the uncrossing price and volume that would result if the auction ran now.

## Three conventions taken from ITCH

A replace that loses queue position is reported as `OrderRemoved` then `OrderRested`, so the layer
above needs no special handling for replace.

A resting order that is fully executed gets no removal event; a consumer tracking quantity sees it
reach zero. Recorded here because the other reading is that an event is missing.

Hidden quantity is never reported. An iceberg's replenishment appears as an `OrderRemoved` of the
exhausted tranche followed by an `OrderRested` of the next one, which is indistinguishable from a new
order arriving at that price. That is the point of an iceberg, and it means the feed stays sufficient
to rebuild the visible book without revealing what it cannot see.

Between them the events are enough to rebuild the visible book at any point in the stream, which is
the contract with the market data publisher above.
