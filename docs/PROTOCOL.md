# Protocol

The engine's whole interface: commands in, events out, as fixed layout binary messages. The
authoritative layout is the SBE schema; this document says what the messages mean.

SBE is the FIX Trading Community's binary encoding standard and is what CME iLink uses. It is used
here for three reasons. One schema generates both Java and C++ codecs, which is what makes a
cross-language comparison fair. Fields sit at fixed offsets, so an implementation may read them in
place or copy them into objects, and that choice is one of the things this project measures.
And the layout is inherited from a real venue's encoding rather than invented to suit us.

This is an internal format, not a client facing one. A client protocol carries hundreds of fields,
optional groups and session plumbing. A canonical command is about ten fields wide, which is why a
gateway's decode is expensive and the engine's is nearly free.

## Sequencing

Every command carries the sequence number assigned upstream. The engine consumes that order rather
than imposing it, so it never generates an input sequence.

Every event carries two numbers: its own output sequence, and the input sequence of the command that
caused it. Causality is in the data rather than inferred from arrival order.

## Commands

All commands carry a header of message type, schema version, instrument id and input sequence.

`NewOrder` carries client order id, participant id, side, pricing instruction, time in force, a
flags word, price and quantity.

`CancelOrder` carries client order id, participant id and the engine order id to cancel.

`ReplaceOrder` carries client order id, participant id, the engine order id, and the full intended
new quantity and price.

Pricing instruction, time in force and the liquidity flag are three separate fields. A single fused
order type is a profile over those three axes, and keeping them apart from the start avoids a wire
format change later, which is the one change in this project that is genuinely expensive.

Participant id is carried from the start because self match prevention needs it. Nothing reads it
until that policy is decided.

## Events

`OrderAccepted` reports the assigned engine order id.

`OrderRejected` reports a machine readable reason and means no state changed.

`OrderRested` reports side, price and resting quantity for an order that has entered the book. This
is the add-order a market data feed needs.

`OrderExecuted` reports execution id, aggressor order id, resting order id, price and quantity. The
price is the resting order's.

`OrderReduced` reports a new resting quantity for an order that kept its queue position.

`OrderRemoved` reports the quantity removed and why: cancelled, replaced, an immediate-or-cancel
remainder, or a fill-or-kill that was killed.

## Two decisions that follow a real feed

A replace that loses queue position is reported as `OrderRemoved` then `OrderRested`. That is how
ITCH models it, and it means the layer above needs no special handling for replace.

A resting order that is fully executed gets no removal event. A consumer tracking quantity sees it
reach zero. This also follows ITCH, and it is documented here because the alternative reading is
that an event is missing.

Between them, these six events are enough to rebuild the book at any point in the stream, which is
the contract with the market data publisher above.
