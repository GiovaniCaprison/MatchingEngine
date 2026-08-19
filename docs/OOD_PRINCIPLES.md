# Design Principles

Why the code is shaped the way it is. These are the decisions that are easy to make by
accident and expensive to reverse, so they are written down once and referenced from the
source by id.

A note on the word "OOD". Much of what passes for object-oriented design is harmful on a
latency-critical path. Virtual dispatch defeats inlining, encapsulation by getter defeats the
cache, and abstraction layers stop the JIT seeing the whole computation. What survives is the
part that was always valuable: clear ownership of state, narrow interfaces, and making illegal
states unrepresentable. Where classical OOD conflicts with the hardware, the hardware wins on
the hot path and OOD wins at the edges. OOD-3 defines where that border falls so the choice is
never made ad hoc.

---

## OOD-1: Mutation follows ownership

A field may only be mutated by the object that owns the invariant it participates in. If
mutating `x` can break an invariant about `y`, then `x` and `y` are mutated by the same object
in the same method, or the invariant is not an invariant.

Every serious matching engine mutates orders in place, because allocating a replacement order
per partial fill is unaffordable. The useful question is who is permitted to mutate. Uncontrolled
mutation produces the worst bug class in this domain: a book whose aggregate quantities no longer
match the orders inside it. It is silent, it corrupts market data, and by the time it surfaces
the causing call is long gone from the stack.

`LinkedListPriceLevel.fillFirst` is the pattern done right. It mutates the resting order's
filled quantity and the level's running total in one call, so VR-6.1 cannot be observed broken.
The corollary is that `Order.applyFill` must be unreachable from anywhere else, which is why the
entity's mutators are package-private to `com.imc.me.book`.

The ownership chain, read as a containment hierarchy where each row may call down into the row
below and never up or sideways:

| Invariant | Owner |
|---|---|
| A level's total equals the sum of its remaining quantities (VR-6.1) | `PriceLevel` |
| FIFO order within a level (FR-3.2) | `PriceLevel` |
| `ordersById` reflects exactly the resting orders | `BookSide` |
| No empty levels remain (NFR-3.2) | `BookSide` |
| Bids and asks are consistent with each other | `OrderBook` |
| An order's lifecycle state is globally coherent | `OrderRegistry` |

`Matcher` sits beside `OrderBook` in the call graph and owns no invariant, so it mutates only
through `BookSide` and `PriceLevel` methods. That is why `BookSide` exposes `addOrder` and
`remove` rather than exposing its `TreeMap`.

## OOD-2: One writer per book

Exactly one thread mutates a book. No locks, no concurrent collections, no atomics.
Concurrency comes from partitioning books across threads.

Single-threaded is the faster option here. A lock-free single writer keeps the book hot in one
core's cache, needs no cache-line ping-pong, no memory fences on the hot path and no CAS retry
loops. The LMAX Disruptor result, millions of operations per second through a single-threaded
business core, is the canonical demonstration. The second payoff is that determinism (NFR-1) is
free: one writer applying a fixed input sequence produces one output sequence, bit for bit.
Determinism retrofitted onto a multi-writer design is a research project.

`TreeMapBookSide` uses plain `HashMap` and `TreeMap`, and `Sequencer` uses a plain `long++`, as
deliberate assertions of this. Every public engine method is single-writer and not thread-safe by
design.

## OOD-3: The core/edge border

The system has two zones with deliberately different rules. The core (`domain`, `book`,
`matching`, `sequencer`) optimises for mechanical sympathy: primitives, in-place mutation, no
collections in signatures. The edge (`event`, and any future gateway or config) optimises for
clarity: immutable records, sealed hierarchies, self-describing types. Every type belongs to one
zone, and conversion happens at named crossing points.

Most design confusion in a system like this comes from asking one type to satisfy both zones at
once. A `List<Trade>` returned from the matcher is a good API and an unacceptable hot-path
allocation, and `List.copyOf` makes it safer without making it cheaper, so you pay twice and
satisfy neither goal. Naming the border turns that contradiction into a routine conversion step.

The crossings: a wire decoder builds a `NewOrder` and converts decimal to scaled long; the
engine hands a validated command to the book as a mutable entity; the core emits primitives into
sinks; a collecting sink at the edge materialises an immutable `Seq`.

"Be consistent" is bad advice applied globally here. Each zone should be internally consistent
and they should not resemble each other.

## OOD-4: Values are immutable, entities are confined

A value (a trade, a command, a result) is an immutable record with no identity. An entity has
identity, a lifecycle and mutable state. There is one entity type in this system, it lives in the
package that owns it, and its mutators are package-private.

`Order` cannot be a record. It needs in-place partial fills, identity semantics (two orders with
equal fields are different orders), and eventually pooling. Its `next` and `prev` are book
mechanics rather than domain concepts: an intrusive list, where the order is the node, saves an
allocation and an indirection per resting order, which makes `Order` the book's node type and
places it in `com.imc.me.book`. Outside that package the edge sees `OrderView`, an interface of
getters, so nobody outside the book can name a mutating method.

Note the asymmetry with OOD-1. `PriceLevel.fillFirst` mutating a passed-in order would be
feature envy in classical OOD. It is correct here because `PriceLevel` owns the invariant
spanning both objects. Ownership of invariants outranks locality of data.

## OOD-5: Validate at the boundary, trust inside

All input validation happens once, at the outermost entry point. Every layer beneath assumes
valid input and never re-checks. An invalid command produces a typed rejection and zero state
change.

The obvious reason is cost: a tick-size check inside the matching walk runs millions of times per
second to re-establish something already known. The reason that matters more is that scattered
validation makes "was the book modified?" unanswerable. If the book validated too, some
rejections would land after partial mutation, and rejection would have to become transactional.
Validating strictly before touching state makes API-8.2 true by construction.

`OrderValidator` is called only from `MatchingEngine`. Below that line there are no argument
checks at all. An `if (qty <= 0) throw` in `book` or `matching` is a defect even though it looks
defensive: it means the boundary is not trusted, and it costs latency on every order to catch a
case that cannot occur.

## OOD-6: Outcomes are types

Every operation that can fail returns a sealed result hierarchy. Failure is a value with a
machine-readable reason. No null returns, no boolean success flags, no exceptions for expected
outcomes.

"Order not found" and "price off tick" are ordinary Tuesday. Exceptions for expected outcomes
cost a stack trace fill, lose type information about what went wrong, and let callers forget to
handle them. A sealed interface plus pattern matching means the compiler reports a missing case
at build time rather than a trader reporting it at 09:30.

```java
switch (engine.cancel(uid)) {
  case Cancelled c -> publish(c);
  case NotFound n  -> reply(n.orderId(), "unknown order");
}   // no default: the compiler proves this is complete
```

Two obligations follow. Every failure result carries enough identity for a pipelining client to
correlate it (API-1.2, API-1.3), and result types must not leak mutable collections (API-11.1).

## OOD-7: Seal for exhaustiveness, not for access control

Use `sealed` where a caller benefits from an exhaustive switch over a closed set of
alternatives. Interfaces designed for future substitution stay open.

Sealing has one payoff and one cost. The payoff is exhaustiveness checking. The cost is that
every new implementation edits the parent's `permits` clause in another file. Where you collect
the payoff, the cost is trivially worth it; elsewhere you have imposed a maintenance tax while
signalling that a set you intend to extend is closed.

The result hierarchies are sealed. `OrderBook`, `OrderBookReader` and `OrderBookWriter` are not,
because nobody will switch over book implementations and `ArrayOrderBook` is planned. The
reader/writer split stays for a different reason: it lets a market-data consumer be handed a
reader with no ability to mutate, which is capability narrowing via types.

The heuristic: is there, or will there be, a switch over this type? If no, do not seal.

## OOD-8: Variation by data, not by subtype

Behavioural variation on the hot path is expressed as data plus a switch. One order type, one
flat layout, one `OrderType` field. There is no `IocOrder`, no `MarketOrder`, no `IocMatcher`.

This is the principle that most directly contradicts textbook OOD, so the reasoning matters.
HotSpot inlines monomorphic call sites and handles bimorphic ones with a cheap type check. At
three or more receiver types the site goes megamorphic: a real virtual call through a vtable, no
inlining through it, and every optimisation that depended on seeing the callee body dies at that
wall. Five order-type implementations behind one interface would put a megamorphic call in the
hottest loop in the system. An enum switch compiles to a `tableswitch`: one bounds check and an
indirect jump, with the JIT free to specialise the hot arm.

Structurally, the walk is identical for every order type. What varies is what happens before and
after it, which gives three phases:

```
1. GATE       type-dependent, pre-trade     POST rejects if it would cross;
                                            FOK probes fillable quantity and aborts if short.
2. WALK       type-agnostic, the hot loop   consume crossing liquidity, emit trades.
3. REMAINDER  type-dependent, post-trade    LIMIT rests; MARKET and IOC cancel;
                                            FOK and POST are unreachable by construction.
```

The gate exists because of FOK: fill-or-kill cannot be a remainder policy, since by the time the
remainder is known you have traded and cannot untrade. The probe uses the same crossing logic as
the walk, so it lives on `Matcher` as `fillableQty` rather than in the book. Two copies of a
crossing check that can drift apart is the bug that placement avoids.

`OrderType` currently conflates three axes that are separate fields in FIX and in every
production engine:

| Axis | FIX tag | Values |
|---|---|---|
| Pricing instruction | `OrdType<40>` | LIMIT, MARKET |
| Time in force | `TimeInForce<59>` | GTC, DAY, IOC, FOK |
| Liquidity constraint | `ExecInst<18>` | none, POST-ONLY |

Today's enum is a profile that expands to a point in that space: `IOC` means
`(LIMIT, IOC, none)`. When limit-IOC and market-IOC need to differ, the migration is three narrow
fields on the entity, expanded at the validation boundary. Because they are data on a flat
layout, the change touches the gate and remainder switches and the walk never learns about it.
Had order types been subtypes, the same change would demand `MarketIocPostOrder` and the
combinatorial explosion behind it.

## OOD-9: Push, don't pull

The core never returns a collection. It emits results into a caller-supplied sink, one
primitive callback per result. Materialising objects is the edge's job, at the edge's expense.

A returned collection forces an allocation the caller cannot decline, at a size unknown in
advance, so it is usually a growable structure with copying. Defensive copying allocates a second
time to buy immutability the core did not need. A returned collection cannot satisfy both a safe
API and zero allocation, so the way out is to invert the direction of data flow. This is the
standard idiom in LMAX, Aeron and Chronicle. It also gives streaming semantics: a consumer can
act on trade 1 before trade 7 exists, which matters for a real outbound feed.

```java
public interface TradeSink {
  void onTrade(long aggressorId, long restingId, long price, long qty);
}

void match(Order aggressor, BookSide opposing, TradeSink sink);   // returns nothing
```

`List` cannot appear in a public signature at all, because API-11.1 catches record accessors too:
`Depth.levels()`, `Accepted.fills()`. Edge DTOs therefore carry `Seq`, an immutable indexed
sequence backed by a private array, which is immutable in the type rather than immutable while
you trust the constructor.

A known future collision: `submit` returning a result object allocates once per order. The
lowest-latency engines do not return from the write path at all, they publish acceptance and
rejection into the same outbound ring buffer. The sealed results are right for a
request/response API, so they stay, but OOD-6 and OOD-11 meet at that seam.

## OOD-10: Every output is bounded

Any query whose result size depends on book state takes an explicit bound from the caller.

An unbounded depth query is linear in the number of price levels, in both time and allocation,
and the size is chosen by whoever is currently spamming the book. One client asking for depth on
a book with fifty thousand levels stalls the writer thread. Real market data feeds are
depth-limited for exactly this reason, and every venue's protocol reflects it. `depth` therefore
takes a required `maxLevels` on both `BookSide` and `OrderBookReader`.

## OOD-11: Allocation is a design property

The allocation cost of a hot-path operation is part of its contract, decided when the signature
is written. Target: zero allocations per order submitted on the steady-state path.

On the road to millions of operations per second the enemy is GC pauses rather than CPU cycles. A
single 50ms stop-the-world pause is a catastrophe for an exchange and no amount of average-case
speed compensates. Allocation is structural: you cannot optimise it out of an API that returns
collections, so deferring it means redesigning the API later. That is why OOD-9 and OOD-10 are
design principles rather than performance tips.

The steady-state budget:

| Operation | Allowed allocations |
|---|---|
| submit, resting | 1, the order entity, eventually 0 from a slab |
| submit, fully filled | 0 |
| cancel, amend | 0 |
| top of book | 0, though the returned record allocates today |
| depth, bounded, into a sink | 0 |

The write path is shaped for this: sinks, primitives, enum outcomes, a reused stamping sink, a
listener array rather than a list. Nothing measures it yet. The enforcement mechanism is JMH's
`gc.alloc.rate.norm`, where zero is a checkable assertion, and until that runs the budget is a
wish.

## OOD-12: No floating point anywhere near a price

Prices and quantities are scaled longs throughout. `double`, `float` and `BigDecimal` appear
nowhere in the core. Conversion happens at the I/O edge using the instrument's price scale.

Binary floating point cannot represent most decimal prices exactly. The consequences are equality
bugs, where two "equal" prices fail to compare equal and a price level silently splits in two,
accumulating drift in aggregate quantities, and behaviour that looks non-deterministic and
destroys replay. `BigDecimal` is exact but allocates per operation and is an order of magnitude
slower, which is unacceptable in the walk. Scaled integers are exact and fast, and the only cost
is remembering the scale, which is what `Instrument.priceScale` carries.

At scale 4, `100.25` is stored as `1002500`. Two incidental `Math.max` calls were rewritten as
ternaries rather than weaken the rule.

## OOD-13: Identity and order come from one place

Order ids and event sequence numbers are minted at one point, the sequencer, from a monotonic
counter. Nothing else generates identity. No timestamps, no UUIDs, no per-class counters.

Determinism means the same input sequence produces the same output sequence, bit for bit, which
is impossible if identity comes from a clock, from randomness, or from several counters whose
interleaving varies. A single monotonic sequence is also the total order the whole system agrees
on, which makes replay possible and makes "did A precede B?" answerable without a clock. Time
priority then needs no timestamp at all: arrival order is sequence order, and FIFO within a level
encodes it structurally.

`Trade` carries its sequence number, which is what makes NFR-1.1 and NFR-1.2 provable by
comparing values rather than comparing whole collections positionally.

## OOD-14: One concept, one home

Every piece of state has one authoritative owner. If two structures both know a fact, one of
them is a cache with an explicit invalidation rule.

Duplicated state with divergent lifetimes is the second worst bug class here, after broken
aggregates. It is insidious because both copies are locally correct and only their relationship
is wrong, so no single-object test can catch it.

Order status is the live example. Filled and cancelled orders leave the resting set, so status
cannot be answered from the book alone. The tempting fix, keeping dead orders in the side's
`ordersById`, would give that map two meanings and two lifetimes and force every later reader to
guess which applied. Instead:

| State | Owner | Lifetime |
|---|---|---|
| Which orders are resting, and where | `BookSide.ordersById` | while resting |
| Every order the engine accepted, and its terminal state | `OrderRegistry` | session |

The book stays a book. The registry lives above it and is the only thing that answers FR-5.4. It
also refuses to duplicate derivable state: quantities are read through to a live `OrderView`, and
only non-derivable terminal states are recorded.

The question to ask: for this fact, which single object would I ask? If the answer is "either of
two", fix it.

## OOD-15: Removal means detachment

When an object leaves a structure, every reference in and out of it is cleared in the same
operation. A removed order has null links and appears in no index.

A half-detached node is the closest thing Java has to a use-after-free. It looks alive,
arithmetic on it succeeds, and a later traversal walks through it into a part of the book that has
moved on, corrupting the live structure through a stale pointer. The GC guarantees the memory is
valid, not that it is still part of the book. Detaching on exit also makes a node's state a pure
function of its most recent insert, which is what makes reuse safe: an amend re-append, or an
order from a pool.

## OOD-16: Preconditions over defensive checks

A method with a narrow contract documents its precondition and does not check it at runtime.
Callers are responsible, and preconditions are verified by tests.

This is the complement of OOD-5. Once input is validated at the boundary an internal re-check is
a branch that always goes one way, and it is also a claim about the contract, because it implies
callers may legitimately violate it and it converts a programming error into a data value.
Contrast OOD-6: an expected outcome like "no such order" is a typed value, whereas a precondition
violation is a bug.

`bestLevel`, `get`, `first`, `fillFirst` and `reduce` all state their preconditions in javadoc.

One sentinel hazard worth writing down. A market order carries an extreme price so it crosses
every level with no special case in the walk, but `TreeMapBookSide.remove` looks up a level by
`order.price()`, and with a sentinel key that lookup is meaningless. It is harmless only because a
market order never rests. That is a real coupling between two distant pieces of code, documented
at both ends, and it becomes a corruption bug the day stop orders arrive.

## OOD-17: Abstract for planned substitution only

An interface exists to support a substitution that is actually planned, or to narrow a
capability. Not for testability, not for symmetry, not in case.

Speculative interfaces cost indirection at runtime and navigation at read time, and they tend to
codify the first implementation's assumptions as though they were universal, which leaves the
second implementation fighting the interface. On the hot path a single-implementation interface is
usually free, since the JIT devirtualises one receiver type, so the cost is mainly comprehension
until a third implementation arrives and OOD-8's megamorphic wall applies.

| Interface | Verdict |
|---|---|
| `OrderBook` | keep, `ArrayOrderBook` is planned |
| `OrderBookReader` / `Writer` | keep, capability narrowing |
| `Matcher` | keep, price-time versus pro-rata is a real venue variation |
| `PriceLevel` | keep and watch, a flat-array variant is plausible |
| `BookSide` | keep and watch |
| `TradeSink` / `DepthSink` | keep, collecting versus publishing versus counting |

The watch entries carry a rule: at most two implementations of any hot-path interface. Beyond
two, convert to data plus a switch. The question to ask of a new interface is to name the second
implementation; if you cannot, write the class.

## OOD-18: Indirection is provisional

Today's object references and `TreeMap` lookups are an implementation choice with a known
successor: flat arrays indexed by int. Write core code so that migration is mechanical.

Twenty million operations per second is not reachable through a `TreeMap<Long, PriceLevel>`.
Every node is a separate heap object, so walking price levels is a chain of cache misses, and
boxed keys make it worse. The endgame is a price-indexed ladder with orders in a slab, int indices
instead of references, and no per-order allocation. That rewrite is tractable if the surrounding
code treats indirection as an implementation detail.

The forward-compatibility rules: `Order` as an intrusive node already has the right shape, since
`next` and `prev` become slab indices with the linking logic unchanged; never use an order's
object identity as meaningful, because identity is `orderId`; keep `PriceLevel` and the order node
as separate types, since levels become array slots and orders become slab rows; prefer index and
count returns over collection returns.

The `TreeMap` implementation stays forever as the correctness oracle to differential-test
against.

---

## Applying this

When adding code, in order: which zone, core or edge (OOD-3)? Who owns the invariant this
touches (OOD-1)? Does it allocate on the hot path (OOD-11)? Is the variation data or subtype
(OOD-8)? Can the compiler enforce the constraint instead of a comment?

When a principle is violated, either fix the code or change this document and say why. A
principle nobody follows teaches readers that the documented design is fiction.
