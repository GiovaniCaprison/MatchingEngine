# Principles

Why the code is shaped the way it is. These are the decisions that are cheap to make by accident and
expensive to reverse, so they are written once and referenced from source by id.

Several of these run against conventional object oriented advice. Virtual dispatch defeats inlining,
encapsulation by getter defeats the cache, and layers of abstraction stop the JIT seeing the whole
computation. What holds up is clear ownership of state, narrow interfaces, and making illegal states
unrepresentable. Where those conflict with the hardware, the hardware wins inside the book and they
win at the edges. P-3 sets the border.

## P-1: The engine is a function of its input log

Given the same ordered commands, the engine produces the same events, byte for byte. No clock, no
randomness, no dependence on wall time or thread interleaving.

Four things follow. Replay reproduces a book exactly, which is how recovery works without the engine
implementing it. A failure is reproducible from its input log. Two implementations can be compared,
because identical output is checkable. And an implementation in another language can be held to the
same logs.

## P-2: One writer per book

Exactly one thread mutates a book. No locks, no concurrent collections, no atomics. Concurrency
comes from partitioning instruments across engines.

Single threaded is the faster option here. A lock free single writer keeps the book in one core's
cache, needs no cache line ping-pong, no fences on the hot path and no retry loops. The LMAX
Disruptor result is the canonical demonstration. The second payoff is that P-1 is free: one writer
applying a fixed sequence produces one output sequence.

## P-3: The protocol is the border

Inside the border, code optimises for mechanical sympathy: primitives, in place mutation, fixed
layouts, no collections in signatures. Outside it, code optimises for clarity. The border is the
message format, and conversion happens at it.

One type cannot satisfy both sides. A collection returned from the matcher is a good API and an
unacceptable allocation, and copying it defensively adds a second allocation without removing the
first. Naming the border turns the contradiction into a conversion step.

Consistency is a property of one side. The two are not expected to resemble each other.

## P-4: Mutation follows ownership

A field may only be mutated by whatever owns the invariant it participates in. If changing one field
can break an invariant about another, both are changed in the same place, or it was never an
invariant.

Every serious engine mutates orders in place, since allocating a replacement per partial fill is
unaffordable, so the constraint is on who may mutate. Uncontrolled mutation produces the worst defect
in this domain: a book whose aggregate quantities no longer match the orders inside it. It is silent,
it corrupts the outbound feed, and it surfaces long after the call that caused it.

## P-5: Validate at the boundary, trust inside

All validation happens once, at the outermost entry point. Everything below assumes valid input and
never re-checks. An invalid command produces a typed refusal and changes nothing.

The obvious reason is cost: a tick check inside the walk runs millions of times a second to
re-establish something already known. The better reason is that scattered validation makes "was the
book modified?" unanswerable, because some refusals then land after partial mutation and refusal has
to become transactional. Checking strictly before touching state makes that question free.

A defensive check below the boundary is a defect even though it looks careful. It says the boundary
is not trusted, and it costs latency on every command to catch a case that cannot happen.

## P-6: Failure is a value with a reason

Every operation that can fail reports a machine readable reason. No exceptions for expected outcomes,
no boolean success flags, no nulls crossing the border.

Order not found and price off tick are routine outcomes. An exception for one costs a stack trace
fill, discards the information about what went wrong, and lets a caller forget to handle it. Reasons
are split finely enough to act on: a tick violation tells a client to fix its rounding, where a
generic refusal tells it nothing.

## P-7: Variation is data, not subtype

Behavioural variation on the hot path is a field and a switch. One order representation, one flat
layout. There is no market order class and no immediate-or-cancel matcher.

HotSpot inlines a call site with one receiver type and handles two cheaply. At three or more it goes
megamorphic: a real virtual call, no inlining through it, and every optimisation that depended on
seeing the callee dies there. Five order types behind one interface puts that in the hottest loop in
the system. An enum switch compiles to a jump table, and the JIT stays free to specialise the hot arm.

Structurally the walk is identical for every type. Only what happens before and after it differs, so
subclassing per type would duplicate the shared work in order to vary the rest.

## P-8: Push, don't pull

The core never returns a collection. It claims space from a caller supplied publisher, encodes one
result into it and commits. Materialising objects is the edge's job, at the edge's expense.

A returned collection forces an allocation the caller cannot decline, at a size unknown in advance.
Copying defensively allocates a second time to buy immutability the core did not need. No returned
collection satisfies both a safe API and zero allocation, so the way out is to invert the direction.
This is the standard idiom in LMAX, Aeron and Chronicle. It also gives streaming: a consumer can act
on the first execution before the last one exists.

Claiming space rather than handing over a filled buffer is the same argument one level down. A
consumer handed bytes has to copy them into whatever it publishes from, and it has to do its work on
the engine's thread, so counting events or checksumming a stream lands inside the command those
checks exist to protect. Encoding into the slot the consumer will read removes the copy and puts the
consumer on another core.

## P-9: Work per command is bounded by state, never by a caller

The work one command causes is bounded by the state it has to touch. No command does work whose size
a caller chose.

Some commands are unavoidably large. A mass cancel touches every resting order for a participant, a
market order can sweep the book, and a stop cascade can chain. Real engines have those spikes and
cannot design them away, and each one is bounded by the book rather than by a request.

What this forbids is the other kind. A depth query taking a level count is bounded by whoever is
currently asking, so one client can stall the writer and everyone behind it. This engine has no query
commands at all, and a consumer builds the book from the event stream, which is how real feeds avoid
the same problem.

## P-10: Allocation is part of the contract

The allocation cost of an operation is decided when its signature is written, not discovered later
with a profiler.

On the road to millions of operations a second the enemy is pauses, not cycles, and one fifty
millisecond stop is a catastrophe no average case compensates for. Allocation is structural: it
cannot be optimised out of an API that returns objects, so deferring it means redesigning the API
later. P-8 and P-9 are the shape this takes in an API.

An implementation may decide to allocate. Copying each command into objects is a legitimate design
and real systems ship it. What is not legitimate is claiming a zero allocation steady state without
measuring it.

## P-11: No floating point near a price

Prices and quantities are scaled integers throughout. Conversion happens at the outermost edge using
the instrument's scale.

Binary floating point cannot represent most decimal prices exactly. The consequences are equality
bugs, where two equal prices fail to compare equal and a price level silently splits in two,
accumulating drift in aggregates, and behaviour that looks non-deterministic and destroys replay.
Arbitrary precision decimal is exact but allocates per operation and is an order of magnitude slower,
which the walk cannot afford. Scaled integers are exact and fast, and the only cost is remembering
the scale.

## P-12: One concept, one home

Every piece of state has one authoritative owner. If two structures both know a fact, one of them is
a cache with an explicit invalidation rule.

Duplicated state with different lifetimes is the second worst bug here, after broken aggregates. It
is insidious because both copies are locally correct and only their relationship is wrong, so no
test of a single object catches it. For any fact there should be one structure to ask. Two is a
defect.

## P-13: Removal means detachment

When an object leaves a structure, every reference into and out of it is cleared in the same
operation.

A half detached node is the closest thing a managed language has to a use after free. It looks alive,
arithmetic on it succeeds, and a later traversal walks through it into a part of the book that has
moved on. The collector guarantees the memory is valid, not that it is still part of the book.
Detaching on exit makes a node's state a function of its most recent insert, which is what makes
reuse and pooling safe.

## P-14: Preconditions over defensive checks

A method with a narrow contract documents its precondition and does not check it at runtime. Callers
are responsible and tests verify it.

This is the complement of P-5. Once input is validated at the boundary an internal re-check is a
branch that always goes one way, and it is also a claim about the contract, because it implies a
caller may legitimately violate it and it turns a programming error into a data value. Contrast P-6:
an expected outcome is a reported reason, and a precondition violation is a bug.

## P-15: Abstract only for a substitution you can name

An interface exists to support a substitution that is actually planned, or to narrow a capability.
Not for testability, not for symmetry, not in case.

A speculative interface costs indirection at runtime and navigation at read time, and it tends to
freeze the first implementation's assumptions into a shape the second has to fight. On the hot path a
single implementation behind an interface is usually free, since the JIT devirtualises one receiver,
so the cost is comprehension until a third arrives and P-7's wall applies.

A new interface needs a named second implementation. Without one, write the class.

## P-16: No cost without a venue that pays it

A field, a check or an indirection on the hot path has to exist in a real engine or earn its place
against measurement. Convenience for the study is not a reason.

The engine is a research subject, and the research is about engines a real venue would run, so the
shape under measurement is the shape a real engine carries. Where convenience for the study and
that shape conflict, the real shape wins, because a measurement taken on something nobody would
ship measures nothing anybody needs.

Two decisions this has already settled. An event carries its own sequence and nothing about its
cause, which is how ITCH works and saves eight bytes and a store on every event published. And
feature cost is measured by varying the input or by writing a second honest engine, never by a
runtime flag, because a disabled feature behind a branch still occupies the method, the object layout
and the inlining budget.

Where a compromise is genuinely unavoidable, it is recorded here with what it costs.

## P-17: Representation is provisional

Today's objects, references and sorted maps are one choice with known successors: flat arrays indexed
by integer, orders in a slab, fields read in place out of a buffer. Write code so that migration is
mechanical.

A tree of heap objects cannot reach the throughput this project is aimed at, because walking price
levels becomes a chain of cache misses and boxed keys make it worse. That rewrite stays tractable
only while the surrounding code treats representation as an implementation detail: never use object
identity as meaningful, keep the level container and the order node as separate concepts, and prefer
returning counts and indices over returning objects.

Here the successors are the upper rungs of the ladder, and comparing them is the study.

## Applying this

When adding code, in order: which side of the border (P-3)? Who owns the invariant this touches
(P-4)? What does it allocate (P-10)? Does a real engine pay this cost (P-16)? Is the variation data
or subtype (P-7)? Can the compiler enforce the constraint instead of a comment?

When a principle is violated, either fix the code or change this document and say why.
