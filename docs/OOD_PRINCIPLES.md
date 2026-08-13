# Object-Oriented Design Principles

The design rules this engine is built on, why each one exists, and how each is enforced.

`ENGINEERING_GUIDE.md` says *what* to build and `TESTING.md` says *how to prove it works*.
This document says **why the code is shaped the way it is** — the decisions that are easy to
make accidentally and expensive to reverse.

Every principle has the same five parts:

* **Rule** — the one-line statement. If you remember nothing else, remember these.
* **Why** — the failure it prevents. A rule you can't justify is cargo cult.
* **Here** — how it shows up in this codebase specifically.
* **Enforcement** — the test, compiler feature, or review check that makes it real.
* **Status** — `enforced`, `partial`, or `aspirational`. Honesty beats aspiration.

> **A note on "OOD" in a latency-critical system.** Much of what passes for
> object-oriented design — deep hierarchies, polymorphism as the default extension
> mechanism, "tell don't ask" applied to every field — is actively harmful on a hot path.
> Virtual dispatch defeats inlining, encapsulation-by-getter defeats the cache, and
> abstraction layers defeat the JIT's ability to see the whole computation. What survives
> is the part of OOD that was always the valuable part: **clear ownership of state, narrow
> interfaces, and making illegal states unrepresentable.** That is the tradition this
> document draws on. Where classical OOD and mechanical sympathy conflict, mechanical
> sympathy wins on the hot path and classical OOD wins at the edges — and **Principle
> OOD-3 defines exactly where that border is**, so the choice is never ad hoc.

---

## Index

| ID | Principle | Status |
|----|-----------|--------|
| [OOD-1](#ood-1--mutation-follows-ownership) | Mutation follows ownership | enforced |
| [OOD-2](#ood-2--one-writer-per-book) | One writer per book | enforced |
| [OOD-3](#ood-3--the-core-edge-border) | The core/edge border | enforced |
| [OOD-4](#ood-4--values-are-immutable-entities-are-confined) | Values are immutable, entities are confined | enforced |
| [OOD-5](#ood-5--validate-at-the-boundary-trust-inside) | Validate at the boundary, trust inside | enforced |
| [OOD-6](#ood-6--outcomes-are-types-not-booleans-nulls-or-exceptions) | Outcomes are types | enforced |
| [OOD-7](#ood-7--seal-for-exhaustiveness-not-for-access-control) | Seal for exhaustiveness, not access control | enforced |
| [OOD-8](#ood-8--variation-by-data-not-by-subtype) | Variation by data, not by subtype | enforced |
| [OOD-9](#ood-9--push-dont-pull) | Push, don't pull | enforced |
| [OOD-10](#ood-10--every-output-is-bounded) | Every output is bounded | enforced |
| [OOD-11](#ood-11--allocation-is-a-design-property) | Allocation is a design property | partial |
| [OOD-12](#ood-12--no-floating-point-anywhere-near-a-price) | No floating point near a price | enforced |
| [OOD-13](#ood-13--identity-and-order-come-from-one-place) | Identity and order come from one place | enforced |
| [OOD-14](#ood-14--one-concept-one-home) | One concept, one home | enforced |
| [OOD-15](#ood-15--removal-means-detachment) | Removal means detachment | enforced |
| [OOD-16](#ood-16--preconditions-over-defensive-checks) | Preconditions over defensive checks | enforced |
| [OOD-17](#ood-17--abstract-for-planned-substitution-only) | Abstract for planned substitution only | partial |
| [OOD-18](#ood-18--indirection-is-provisional) | Indirection is provisional | aspirational |

---

## OOD-1 — Mutation follows ownership

**Rule.** A field may only be mutated by the object that owns the invariant that field
participates in. If mutating `x` can break an invariant about `y`, then `x` and `y` are
mutated by the same object, in the same method, or the invariant is not an invariant.

**Why.** "Which objects are mutable" is the wrong question — every serious matching engine
mutates orders in place, because allocating a replacement order per partial fill is
unaffordable. The right question is *who is permitted to mutate*. Uncontrolled mutation
produces the single worst bug class in this domain: a book whose aggregate quantities no
longer match the orders inside it. That bug is silent, it corrupts market data, and by the
time you see it the causing call is long gone from the stack.

**Here.** `LinkedListPriceLevel.fillFirst(qty)` is this principle done correctly: it
mutates the resting order's `filledQty` **and** the level's `totalQty` in one call, so
VR-6.1 (`totalQty == Σ remaining`) cannot be observed broken. `PriceLevel` owns that
invariant, so `PriceLevel` performs both halves of the mutation. The corollary is that
`Order.applyFill` must be unreachable from anywhere else — otherwise a caller can perform
half of the pair.

The ownership chain in full:

| Invariant | Owner | Mutating methods |
|-----------|-------|------------------|
| `totalQty == Σ remaining` in a level (VR-6.1) | `PriceLevel` | `add`, `remove`, `fillFirst`, `reduce` |
| FIFO order within a level (FR-3.2) | `PriceLevel` | `add`, `remove` |
| `ordersById` reflects exactly the resting orders | `BookSide` | `addOrder`, `remove` |
| No empty levels remain (NFR-3.2) | `BookSide` | `remove` |
| Bids and asks are consistent with each other | `OrderBook` | `submit`, `amend`, `cancel` |
| An order's lifecycle state is globally coherent | `OrderRegistry` | via `OrderBook` only |

Read that table as a containment hierarchy: each row may call *down* into the row below it
and never *up* or *sideways*. `Matcher` is the interesting case — it sits beside
`OrderBook` in the call graph but owns no invariant, so it mutates **only** through
`BookSide`/`PriceLevel` methods. That is exactly why `BookSide` exposes `addOrder`/`remove`
rather than exposing its `TreeMap`.

**Enforcement.**
* Compiler: the mutators on the order entity are **package-private** to `com.imc.me.book`.
  Java has no `friend`, and package-private is the closest thing — which is *why* the
  mutable entity lives in the `book` package (see OOD-4).
* Test: VR-6.1 and NFR-3.1 in the property layer reconcile aggregates against raw orders
  after randomised command streams. A violation of this principle shows up there.
* ArchUnit (to add): no class outside `com.imc.me.book..` may call the entity's mutators —
  this is the concrete meaning of NFR-4.1, which is currently only a TODO comment in
  `ArchitectureTest`.

**Status.** `enforced` — the order entity lives in `com.imc.me.book` and its mutators are
package-private, so outside that package a mutator cannot be *named*, let alone called. Read-only
consumers use `OrderView`. See PR NFR-4.1.

---

## OOD-2 — One writer per book

**Rule.** Exactly one thread mutates a book. No locks, no concurrent collections, no
atomics in the book. Concurrency is achieved by *partitioning* books across threads, never
by sharing one.

**Why.** This is counter-intuitive until you internalise it: single-threaded is not the
simple-but-slow option, it is **the fast option**. A lock-free single writer keeps the book
hot in one core's L1/L2, needs no cache-line ping-pong between cores, no memory fences on
the hot path, and no CAS retry loops. The LMAX Disruptor result — millions of ops/sec
through a single-threaded business-logic core — is the canonical demonstration. Two threads
mutating one `TreeMap` under a lock is slower *and* wrong.

The second payoff is free determinism (NFR-1): one writer applying a fixed input sequence
produces one output sequence, bit for bit. Determinism retrofitted onto a multi-writer
design is a research project; determinism from single-writer is an accident of the
architecture.

**Here.** `TreeMapBookSide` uses plain `HashMap` and `TreeMap`, not concurrent variants —
that is a deliberate assertion of this principle, not an oversight. Parallelism, when it
comes, is one book per symbol per thread, with a sequencer as the single ordered ingress
point.

**Enforcement.**
* ArchUnit (to add, NFR-4.1): no class in `com.imc.me..` may depend on
  `java.util.concurrent..`, `java.lang.Thread`, or `synchronized`-adjacent utilities.
  Note the intent is inverted from a typical rule — we are banning thread-safety
  machinery, because its presence means someone assumed sharing was allowed.
* Documentation: the threading contract is stated on the public API surface. Every public
  engine method is documented as "single-writer; not thread-safe by design."

**Status.** `enforced` — `no_thread_safety_machinery_in_the_core` bans `java.util.concurrent`
and `java.lang.ref` outright, so the first reach for a `ConcurrentHashMap` to paper over a symptom
fails the build. `Sequencer` uses a plain `long++` for the same reason.

---

## OOD-3 — The core/edge border

**Rule.** The system has two zones with **different and deliberately inconsistent** design
rules. The **core** (`domain`, `book`, `matching`, `sequencer`) optimises for mechanical
sympathy: primitives, mutation, zero allocation, no collections in signatures. The **edge**
(`event`, `gateway`, `config`, the REST layer) optimises for clarity and safety: immutable
records, sealed hierarchies, self-describing types. Every type belongs to exactly one zone,
and conversion happens at named crossing points.

**Why.** Most design confusion in a system like this comes from one type being asked to
satisfy both zones' rules at once, which is impossible. A `List<Trade>` returned from the
matcher is a good API and an unacceptable hot-path allocation; `List.copyOf` makes it
*safer* without making it *cheaper*, so you end up paying twice and satisfying neither goal.
Naming the border converts that contradiction into a routine conversion step.

This also explains why "be consistent" is bad advice applied globally here. The core and
edge should each be internally consistent and *should not* match each other. Consistency is
a property of a zone, not of a repository.

**Here.** The crossing points, and what happens at each:

| Crossing | Inbound conversion | Outbound conversion |
|----------|-------------------|---------------------|
| REST/gateway → engine | wire JSON → `NewOrder` command; decimal → scaled `long` | `SubmitResult` → JSON |
| `MatchingEngine` → `OrderBook` | validated command → mutable entity | primitives via sinks |
| `Matcher` → caller | — | `TradeSink` callbacks (primitives, no objects) |
| engine → event consumer | — | collecting sink builds immutable `Seq` |

The rule that makes this practical: **a sink is the outbound crossing point.** The core
emits primitives into a sink; whoever owns the edge implements a sink that materialises
objects. See OOD-9.

**Enforcement.**
* ArchUnit: `event..` may not be depended on *by* `matching..` (the core must not know
  about edge DTOs). Currently violated — `Matcher` returns `List<Trade>` and `BookSide`
  returns `List<Depth.Level>`, both of which drag edge types into the core.
* API-11.1 / FR-5.5 in the structural layer enforce the edge half (immutable outputs only).

**Status.** `enforced` — `matching_does_not_depend_on_edge_dtos` holds the border, and the
crossings are named types: sinks outbound, `NewOrder` inbound, `Seq` for materialised sequences.

---

## OOD-4 — Values are immutable, entities are confined

**Rule.** A **value** (a trade, a price level snapshot, a command, a result) is an
immutable `record` with no identity. An **entity** (an order) has identity, a lifecycle,
and mutable state — there is exactly one entity type in this system, it lives in the
package that owns it, and its mutators are package-private.

**Why.** The value/entity distinction is the oldest useful idea in domain modelling and it
maps almost perfectly onto Java 21's records-vs-classes split. Its practical value here is
that it stops the two questions that keep coming up — "should this be a record?" and "may I
mutate this?" — from needing case-by-case judgement. Records: always immutable, always
values, never in the book's mutable state. Classes: entities, mutable, confined.

**Here.** `Order` is the only entity. Three consequences, none obvious:

1. **`Order` cannot be a record**, and the temptation to make it one should be resisted
   permanently. It needs in-place partial fills (a record would allocate per fill),
   identity semantics (two orders with equal fields are different orders), and eventually
   object pooling. `record Order` would look tidier and be wrong.
2. **`Order`'s `next`/`prev` are book mechanics, not domain concepts.** An intrusive linked
   list — where the order *is* the node rather than being wrapped in one — is the correct
   HFT choice, saving an allocation and an indirection per resting order. But it means
   `Order` is *the book's node type*, and it therefore belongs in `com.imc.me.book`, not in
   `com.imc.me.domain`. Its current location in `domain` is precisely why its mutators had
   to be made `public`, which breaks OOD-1.
3. **Outside the book, `Order` is read-only.** The edge sees an `OrderView` — an interface
   of getters that `Order` implements — or a record snapshot. Nobody outside `book` can
   name a mutating method, so nobody can call one.

Note the deliberate asymmetry with OOD-1: `PriceLevel` mutating a passed-in `Order`
(`fillFirst`) would be a "feature envy" smell in classical OOD. It is correct here,
because `PriceLevel` owns the invariant that spans both objects. Ownership of invariants
outranks locality of data.

**Enforcement.**
* Compiler: entity mutators are package-private; the edge is typed against `OrderView`.
* ArchUnit: every type in `com.imc.me.event..` and `com.imc.me.domain..` is a `record` or
  an `enum` (no mutable state at the edge).
* Review: a new mutable class outside `book` needs a written justification.

**Status.** `enforced` — one mutable class in the system, confined; `dto_types_are_immutable`
holds every DTO to records, enums and interfaces.

---

## OOD-5 — Validate at the boundary, trust inside

**Rule.** All input validation happens once, at the outermost engine entry point. Every
layer beneath it assumes valid input and never re-checks. An invalid command produces a
typed rejection and **zero** state change.

**Why.** Two reasons, and the second is the one people miss. The obvious one: re-validating
in the hot loop is pure cost — a tick-size check inside the matching walk runs millions of
times per second to re-establish something already known. The subtle one: **scattered
validation makes "was the book modified?" unanswerable.** If the book validates too, then
some rejections happen after partial mutation, and now rejection has to be transactional.
Validating strictly before touching state makes API-8.2 ("boundary validation leaves the
book unmodified") true by construction instead of by careful cleanup.

**Here.** `MatchingEngine` is the gate. It owns `Instrument` reference data (tick size, lot
size, price scale) and checks: positive price, positive quantity, price on tick, quantity on
lot, known order type, price within static limits. Each failure maps to a `RejectReason`.
Below that line — `OrderBook`, `BookSide`, `PriceLevel`, `Matcher` — there are **no
argument checks at all**, deliberately.

The `RejectReason` enum existing already implies this design; the gate that produces those
reasons is what's missing.

**Enforcement.**
* Test: API-8.1 and API-8.2 in the explicit layer — reject an invalid order, then assert
  the book is byte-identical to before.
* Test: VR-1.1, VR-2.1, VR-2.2, VR-3.2 pin the individual rules.
* Review: a `if (qty <= 0) throw` appearing in `book..` or `matching..` is a defect even
  though it looks defensive. It means the boundary is not trusted, and it costs latency.

**Status.** `enforced` — `OrderValidator` is called only from `MatchingEngine`, and
`only_the_boundary_validates` bans `book`/`matching` from depending on the validation package at
all. API-8.2 is true by construction: validation runs before anything is touched, so there is no
partial mutation to undo.

---

## OOD-6 — Outcomes are types, not booleans, nulls, or exceptions

**Rule.** Every operation that can fail returns a **sealed** result hierarchy. Failure is a
value with a machine-readable reason. No `null` returns, no `boolean` success flags, no
exceptions for expected outcomes.

**Why.** "Order not found" and "price off tick" are not exceptional; they are ordinary
Tuesday. Exceptions for expected outcomes cost you a stack trace fill (expensive, and
JIT-hostile in a hot path), lose type information about *what* went wrong, and let callers
forget to handle them. A sealed interface plus `switch` pattern matching means the compiler
tells you when you've forgotten a case — the error is found at compile time by a machine
rather than at 09:30 by a trader.

**Here.** `SubmitResult`, `AmendResult`, `CancelResult` with `Accepted` / `Rejected` /
`NotFound` / `Cancelled`. This is the best-executed part of the current codebase and the
reference example for the rest of the edge. Callers use exhaustive `switch`:

```java
switch (engine.cancel(uid)) {
  case Cancelled c -> publish(c);
  case NotFound n  -> reply(n.orderId(), "unknown order");
}   // no default: the compiler proves this is complete
```

Two obligations that follow and are not yet met: every failure result must carry enough
identity to be **correlated** by a client that pipelines requests (a `Rejected` with no
order id is unactionable — API-1.2/API-1.3), and result types must not leak mutable
collections (API-11.1, see OOD-9).

**Enforcement.**
* Compiler: sealed interfaces + exhaustive `switch` with no `default` arm. Adding a result
  type breaks every incomplete consumer at compile time — that's the feature.
* Test: API-9.1 (accept/reject distinguishable as types), API-2.1 (cancel never throws).

**Status.** `enforced` — `Rejected` now carries both the client order id and the engine uid, so a
pipelining client can correlate a refusal. `SealingTest` pins the exhaustiveness that makes the
`switch` above safe.

---

## OOD-7 — Seal for exhaustiveness, not for access control

**Rule.** Use `sealed` where a caller benefits from an exhaustive `switch` over a *closed
set of alternatives*. Do **not** use `sealed` merely to restrict who implements an
interface. Interfaces designed for future substitution stay open.

**Why.** `sealed` has one payoff — exhaustiveness checking — and one cost: every new
implementation edits the parent's `permits` clause, in another file, often in another
conceptual layer. Where you get the payoff, the cost is trivially worth it. Where you
don't, you have imposed a maintenance tax and bought nothing, while also signalling
"this set is closed" about a set you fully intend to extend.

**Here.** Correct use: `SubmitResult`, `AmendResult`, `CancelResult`. Callers switch over
them and exhaustiveness is exactly what you want.

Incorrect use: `OrderBook`, `OrderBookReader`, `OrderBookWriter` are `sealed ... permits`.
Nobody will ever `switch` over book implementations — that would be a type check on the
data structure, which the whole point of the interface is to avoid. Meanwhile
`ENGINEERING_GUIDE.md` explicitly plans `ArrayOrderBook`, so the `permits` clause is a
guaranteed future three-file edit for zero benefit. These should be plain interfaces.

The read/write interface split (`OrderBookReader` / `OrderBookWriter`) is worth keeping for
a different and good reason: it is an **interface segregation** boundary that lets a
market-data consumer be handed a reader with no ability to mutate. That's capability
narrowing via types, which is real. It just doesn't need sealing.

**Enforcement.** Review only. A one-line heuristic that decides it: *"is there, or will
there be, a `switch` over this type?"* If no, don't seal.

**Status.** `enforced` — the book hierarchy is unsealed, results stay sealed, and `SealingTest`
asserts both directions so neither mistake can creep back.

---

## OOD-8 — Variation by data, not by subtype

**Rule.** Behavioural variation on the hot path is expressed as **data plus a `switch`**,
not as an interface with many implementations. One order type, one flat layout, one
`OrderType` field. There is no `IocOrder`, no `MarketOrder`, no `IocMatcher`.

**Why.** This is the principle that most directly contradicts textbook OOD, so the
reasoning matters.

*Mechanically:* HotSpot inlines monomorphic call sites, and handles bimorphic ones with a
cheap type check. At three or more receiver types a call site goes **megamorphic** — the
JIT emits a real virtual call through a vtable, stops inlining through it, and every
optimisation that depended on seeing the callee body (escape analysis, constant folding,
loop unrolling) dies at that wall. Five order-type implementations behind one interface
would put a megamorphic call in the hottest loop in the system. An `enum` `switch` compiles
to a `tableswitch`: one bounds check and an indirect jump, with the JIT free to
profile-specialise the hot arm. Polymorphism costs you the exact thing this project exists
to achieve.

*Structurally:* the matching walk is **identical** for every order type — walk opposing
levels while prices cross, FIFO within a level, trade at the resting price. What varies is
only what happens before and after. Subclassing per type would duplicate the shared 90% to
vary the 10%, and force the 10% to be discovered by reading five classes.

**Here.** Order handling has exactly three phases, and only two of them know about type:

```
1. GATE       type-dependent, pre-trade      POST: reject if it would cross.
                                             FOK:  probe fillable qty; abort if insufficient.
2. WALK       type-AGNOSTIC, the hot loop    consume crossing liquidity, emit trades.
3. REMAINDER  type-dependent, post-trade     LIMIT: rest. MARKET/IOC: cancel.
                                             FOK/POST: unreachable by construction.
```

The `TODO(Step 4)` in `TreeMapOrderBook.submit` sits at phase 3, which is the right home
for it. **Phase 1 has no home yet, and FOK is why it must exist**: FOK cannot be a
remainder policy, because by the time the remainder is known you have already traded and
cannot untrade. FOK needs a read-only *probe* over the opposing side, which is the same
crossing logic as the walk and therefore belongs on `Matcher`
(`long fillableQty(aggressor, opposing)`), not in the book.

**The three-axis model.** `OrderType` today conflates three independent things, and the
enum's own Javadoc already identifies them correctly. They are separate fields in FIX and
in every production engine:

| Axis | FIX tag | Values | Meaning |
|------|---------|--------|---------|
| Pricing instruction | `OrdType<40>` | LIMIT, MARKET | how the price is determined |
| Time in force | `TimeInForce<59>` | GTC, DAY, IOC, FOK | how long it may live |
| Liquidity constraint | `ExecInst<18>` | none, POST-ONLY | may it take liquidity? |

Today's single enum is a **profile** that expands into a point in that space (`IOC` means
`(LIMIT, IOC, none)`). When LIMIT-IOC vs MARKET-IOC is needed, the migration is to give the
entity three narrow fields and expand the profile at the validation boundary. Because they
are *data on a flat layout*, that change touches the gate and remainder switches only — the
walk never learns about it. This is the payoff: had order types been subtypes, the same
change would demand `MarketIocPostOrder` and the combinatorial explosion that kills the
design.

**Enforcement.**
* Review: a new order type must be a new enum arm plus new `switch` arms. A PR adding a
  subclass of the order entity, or a second `Matcher` implementation per order type, is
  rejected on sight.
* Compiler: `switch` over `OrderType` with no `default` arm — adding an enum constant then
  fails compilation at every decision point, which is how you find all of them.
* Test: FR-2.1 through FR-2.6 cover one type each; VR-3.1 runs every type against an empty
  book.

**Status.** `enforced` — all five types are handled by two `default`-less switches in
`TreeMapOrderBook.submit`, the gate has a home, and `Matcher.fillableQty` is the shared probe FOK and
POST both use. `OrderTypePolicyTest` covers every arm with the walk stubbed out — which is itself the
proof the split worked.

---

## OOD-9 — Push, don't pull

**Rule.** The core never *returns* a collection. It **emits** results into a caller-supplied
sink, one primitive callback per result. Materialising objects and collections is the
edge's job, done at the edge's expense.

**Why.** A returned collection forces an allocation the caller cannot decline, and the size
is unknown in advance so it is usually a growable structure with copying. Defensive copying
(`List.copyOf`) makes this *worse*, not better: it allocates a second time to buy
immutability the core didn't need. There is no way to satisfy both "safe API" and
"zero-allocation" with a returned collection — the only way out is to invert the direction
of data flow. This is the standard idiom in LMAX, Aeron, Chronicle, and every low-latency
JVM system.

The secondary benefit is streaming semantics: a consumer can act on trade 1 before trade 7
exists, which matters for a real outbound feed.

**Here.** Sinks are narrow interfaces over **primitives**, so nothing is allocated per
result:

```java
public interface TradeSink {
  void onTrade(long aggressorId, long restingId, long price, long qty);
}

void match(Order aggressor, BookSide opposing, TradeSink sink);   // returns nothing
```

The `Trade` record still exists and is still valuable — it is an **edge** type. A
`CollectingTradeSink` at the edge builds an immutable sequence of them for the REST
response or a test assertion, where allocation is correct because you are about to
serialise anyway.

This is also the real fix for the currently-failing API-11.1. That rule is
`methods().arePublic().notHaveRawReturnType(List.class)`, and it catches **record
accessors** too — `Depth.levels()`, `Accepted.fills()`, `Cancelled.fillsBeforeCancellation()`.
So no amount of `List.copyOf` discipline can ever satisfy it; `List` must leave the
signatures entirely. Edge DTOs therefore carry an immutable indexed sequence type
(`Seq<T>` — `size()`, `get(int)`, `Iterable`, backed by a private array) rather than a
`List`. That satisfies API-11.1 and FR-5.5 *honestly* — genuinely immutable, not
immutable-if-you-trust-the-constructor.

**A known future collision.** `submit` returning a `SubmitResult` object also allocates,
once per order. Terminal-latency engines don't return from the write path at all; they
publish `Accepted`/`Rejected` into the same outbound ring buffer. The sealed result types
are genuinely good for a request/response API, so they stay for now — but be aware OOD-6
and OOD-11 collide at that seam, and the resolution when it matters is the same one:
events out, sealed DTOs at the edge.

**Enforcement.**
* Test: API-11.1 and FR-5.5 in the structural layer — no public method returns `List`,
  `Map`, or `Set`. Extend to `Collection`, `Iterator`, and arrays.
* ArchUnit (to add): no type in `matching..` or `book..` may reference `java.util.stream..`.
  A stream on the hot path is an allocation cascade.

**Status.** `enforced` — `List` is gone from every signature, `Collection` is banned alongside it,
and `java.util.stream` is banned in `book`/`matching`. `Seq` is the sanctioned outbound sequence.

---

## OOD-10 — Every output is bounded

**Rule.** Any query whose result size depends on book state takes an explicit bound from
the caller. No unbounded output, ever.

**Why.** An unbounded `depth()` is O(number of price levels) in both time and allocation,
chosen by whoever is currently spamming the book rather than by the caller. It is a
self-inflicted denial of service: one client asking for depth on a book with 50,000 levels
stalls the writer thread. Real market data feeds are depth-limited (top 5, top 10) for
exactly this reason, and every venue's protocol reflects it.

**Here.** `depth(side)` must become `depth(side, maxLevels)`, and the sink form
`depth(side, maxLevels, sink)`. `TreeMapBookSide.depth()` today streams every level and
copies twice (`Stream.toList()` already returns an unmodifiable list, so the wrapping
`List.copyOf` is a redundant second copy).

**Enforcement.** Review, plus a benchmark: NFR-2.3 asserts top-of-book is constant time,
and a bounded-depth benchmark should show depth cost independent of resting order count.

**Status.** `enforced` — `depth` takes a required `maxLevels` on both `BookSide` and
`OrderBookReader`.

---

## OOD-11 — Allocation is a design property

**Rule.** The allocation cost of a hot-path operation is part of its contract, decided when
the signature is written — not discovered later with a profiler. Target: **zero allocation
per order submitted** on the steady-state path.

**Why.** On the road to millions of ops/sec, the enemy is GC pauses, not CPU cycles. A
single 50ms stop-the-world pause is a catastrophe for an exchange, and no amount of
average-case speed compensates. Allocation is also *structural*: you cannot optimise it out
of an API that returns collections, so "we'll fix allocation later" really means "we'll
redesign the API later."

This is why OOD-9 and OOD-10 are design principles rather than performance tips. They are
the shape allocation-freedom takes in an API.

**Here.** The steady-state allocation budget:

| Operation | Allowed allocations | Notes |
|-----------|--------------------|-------|
| submit (resting) | 1 (the order entity) | eventually 0, from a pool/slab |
| submit (fully filled) | 0 | trades go to a sink as primitives |
| cancel / amend | 0 | |
| top-of-book | 0 | `TopOfBook` is a record — a future collision, see below |
| depth (bounded, sink) | 0 | |

`TopOfBook` as a returned record allocates per query. Acceptable now (queries are not the
hot path), and the eventual fix is a flyweight the caller supplies. Noted rather than
solved — an honest budget beats a pretty one.

**Enforcement.**
* Measurement: JMH `-prof gc` gives `gc.alloc.rate.norm` — **bytes allocated per
  operation**. That number is the enforcement mechanism, and `0` is a checkable assertion,
  not a vibe.
* Profiling: `asprof -e alloc` flamegraphs show which method allocates.
* Deferred until the engine is correct (`TESTING.md` Step 7) — a fast wrong answer is
  worthless.

**Status.** `partial` — the write path is now shaped for it (sinks, primitives, enum outcomes, a
reused stamping sink, a listener array rather than a `List`), but **nothing measures it yet**. This
stays `partial` until JMH reports `gc.alloc.rate.norm`, because a budget nobody checks is a wish.

---

## OOD-12 — No floating point anywhere near a price

**Rule.** Prices and quantities are scaled `long`s throughout. `double`/`float`/`BigDecimal`
appear nowhere in the core. Conversion happens at the I/O edge using the instrument's
`priceScale`.

**Why.** Binary floating point cannot represent most decimal prices exactly: `100.25` is
fine, `0.1` is not. The consequences are equality bugs (two "equal" prices that don't
compare equal, so a price level splits in two), accumulating drift in aggregate quantities,
and non-deterministic-looking behaviour that destroys replay. `BigDecimal` is exact but
allocates per arithmetic operation and is an order of magnitude slower — unacceptable in
the walk. Scaled integers are exact *and* fast; the only cost is remembering the scale,
which is what `Instrument.priceScale` is for.

**Here.** `100.25` at scale 4 is stored as `1002500`. `Order.price`, `Trade.price`,
`PriceLevel.price`, `totalQty` are all `long`. `Instrument` carries `tickSize`, `lotSize`,
`priceScale` as the reference data for edge conversion and tick validation.

**Enforcement.**
* ArchUnit (to add): no field or method signature in `com.imc.me..` uses `double`, `float`,
  `Double`, `Float`, or `BigDecimal`. This is a cheap, absolute rule — add it and it can
  never regress.
* Test: VR-2.2 rejects over-precision prices at the boundary.

**Status.** `enforced` — `no_floating_point_in_the_core` and `no_floating_point_fields` make it
absolute. Two incidental `Math.max` calls were rewritten as ternaries rather than weaken the rule.

---

## OOD-13 — Identity and order come from one place

**Rule.** Order ids and event sequence numbers are minted at exactly one point — the
sequencer — from a monotonic counter. Nothing else generates identity. No timestamps, no
UUIDs, no per-class counters on the hot path.

**Why.** Determinism (NFR-1) means the same input sequence produces the same output
sequence, bit for bit. That is impossible if identity comes from a clock (unreproducible),
randomness (obviously), or several counters (interleaving-dependent). A single monotonic
sequence also *is* the total order the whole system agrees on: it makes replay possible,
makes audit trails meaningful, and makes "did event A precede event B?" answerable without
a clock. Time priority (FR-3.2) then needs no timestamp at all — arrival order is sequence
order, and FIFO within a level encodes it structurally.

**Here.** The sequencer mints order uids and stamps every outbound trade with a monotonic
`sequence`. `Trade` currently has no sequence field, which makes NFR-1.1/1.2 provable only
by comparing whole collections rather than by a stable per-event identity — and makes an
audit trail impossible to reconstruct. Add it before golden fixtures are baked against the
current shape.

**Enforcement.**
* Test: NFR-1.1 and NFR-1.2 in the golden layer — replay identical input, assert identical
  trades and identical final book.
* ArchUnit (to add): no core class may call `System.currentTimeMillis`,
  `System.nanoTime`, `Instant.now`, or `Math.random` / `Random`. Clock and randomness are
  the two ways determinism dies quietly.

**Status.** `enforced` — one `Sequencer` per engine, `Trade` carries its `sequence`, and
`no_clock_or_randomness_in_the_core` bans `java.time`, `Random`, `UUID` and `Math`.
`SequenceDeterminismTest` replays the same input and asserts identical trades, sequence numbers
included.

---

## OOD-14 — One concept, one home

**Rule.** Every piece of state has exactly one authoritative owner. If two structures both
know a fact, one of them is a cache with an explicit invalidation rule, or it is a bug
waiting to happen.

**Why.** Duplicated state with divergent lifetimes is the second-worst bug class in this
domain (after broken aggregates, OOD-1). It is insidious because both copies are locally
correct and only their *relationship* is wrong, so no single-object unit test can catch it.

**Here.** The live example is `orderStatus`. The `TODO` in `TreeMapOrderBook.orderStatus`
correctly identifies that filled and cancelled orders leave the resting set, so status
cannot be answered from `bids`/`asks` alone. The tempting fix — keep dead orders in the
side's `ordersById` — is exactly the bug this principle prevents: `ordersById` would then
have two meanings ("resting here" and "known to the system") with two lifetimes, and every
subsequent reader would have to guess which.

The correct decomposition:

| State | Owner | Lifetime |
|-------|-------|----------|
| Which orders are resting, and where | `BookSide.ordersById` | while resting |
| Every order the engine has ever accepted, and its terminal state | `OrderRegistry` | session |

The book stays a book. The registry lives above it, in the engine, and is the only thing
that answers FR-5.4. `OrderStatus` also needs `remainingQty` and `filledQty` to satisfy
FR-5.4 as written ("incl remaining qty") — it currently carries neither.

**Enforcement.** Review, guided by a question: *for this fact, which single object would I
ask?* If the answer is "either of two", fix it. NFR-3.2 (no orphaned orders) catches the
book half at runtime.

**Status.** `enforced` — `orderStatus` was **removed** from the book and `OrderRegistry` owns
session-lifetime state. It also refuses to duplicate derivable state: quantities are read through to
a live `OrderView`, and only non-derivable terminal states are recorded.

---

## OOD-15 — Removal means detachment

**Rule.** When an object leaves a structure, every reference in **and out** of it is
cleared in the same operation. A removed order has `next == null` and `prev == null` and
appears in no index.

**Why.** A half-detached node is the closest thing Java has to a use-after-free. It looks
alive, arithmetic on it succeeds, and a later traversal walks through it into a part of the
book that has moved on — corrupting the live structure via a stale pointer. In a
manually-linked intrusive list, the GC will not save you: it only guarantees the memory is
*valid*, not that it is *still part of the book*. Detaching on exit also means a node's
state is a pure function of its most recent insert, which is what makes reuse (amend
re-append, object pooling) safe rather than lucky.

**Here.** Two live defects in `LinkedListPriceLevel`:

* `add` sets `order.setNext(null)` but never clears `prev` on the `head == null` path. Today
  orders are freshly constructed so `prev` is `null` by luck. The first time an order is
  re-appended — a qty-increase amend (FR-4.4), or an order from a pool — it carries a stale
  `prev`, and the next `remove` writes through it into a detached node.
* `remove` ends with `prev = next = null;`, which assigns the **local variables** and has
  no effect. The intent was to detach the removed node, which is exactly what this
  principle requires: it should be `order.setNext(null); order.setPrev(null);`.

**Enforcement.**
* Test: NFR-3.2 (no orphaned orders) and VR-4.2 (a swept-clean side leaves a clean empty
  book) in the property layer, exercised by randomised add/remove/re-add streams. A
  targeted regression test for remove-then-re-add is what pins this specific bug.
* Review: every `remove`/`unlink` is read with the question "what still points at this?"

**Status.** `enforced` — both defects fixed, and `PriceLevelNodeTest` pins them; three of its five
cases fail against the old implementation.

---

## OOD-16 — Preconditions over defensive checks

**Rule.** A method with a narrow contract documents its precondition and does not check it
at runtime. Callers are responsible. Preconditions are verified by tests, not by branches
in the hot path.

**Why.** The complement of OOD-5: once input is validated at the boundary, an internal
re-check is a branch that always goes one way — cheap individually, but it is also a *lie
about the contract*, because it implies callers may legitimately violate it and it silently
converts a programming error into a data value. Fail fast in tests; don't pay per operation
in production. (Contrast with OOD-6: **expected** outcomes like "no such order" are typed
values. A precondition violation is a *bug*, not an outcome.)

**Here.** `BookSide.bestLevel()` throws NPE on an empty side (`levels.firstEntry()` returns
`null`). That is fine and fast — but it must be *documented* as requiring `!isEmpty()`,
which is how `topOfBook` already calls it. Undocumented, it's a landmine in the matching
walk; documented, it's a contract. The same applies to `PriceLevel.fillFirst` (requires a
non-empty level and `qty <= first().remainingQty()`) and to the MARKET price sentinel.

**A specific sentinel hazard worth writing down.** A MARKET order carries an extreme price
so it crosses every level with no special case in the walk (`ENGINEERING_GUIDE.md` step 1).
But `TreeMapBookSide.remove` looks up its level via `levels.get(order.price())` — with a
sentinel key, that lookup is meaningless. It is harmless *only* because MARKET never rests.
That is a real coupling between two distant pieces of code, and it must be documented at
both ends or it becomes a corruption bug the day stop orders arrive.

**Enforcement.** Javadoc `@throws`/precondition on every narrow-contract method, plus
VR-3.1 (every order type against an empty book) as the behavioural net.

**Status.** `enforced` — `bestLevel`, `get`, `first`, `fillFirst` and `reduce` all state their
preconditions, and `EmptyBookTest` verifies them, since a documented-not-checked contract has no
runtime enforcement.

---

## OOD-17 — Abstract for planned substitution only

**Rule.** An interface exists to support a substitution that is actually planned, or to
narrow a capability. Not "for testability", not for symmetry, not in case.

**Why.** Speculative interfaces cost indirection at runtime and navigation cost at read
time, and they tend to codify the *first* implementation's assumptions as though they were
universal — which is worse than no abstraction, because the second implementation then has
to fight the interface. A single-implementation interface on the hot path is usually free
(the JIT sees one receiver type and devirtualises), so the cost is mainly comprehension —
but the moment a third implementation appears, OOD-8's megamorphic wall applies.

**Here.** Each existing abstraction, judged:

| Interface | Verdict | Reason |
|-----------|---------|--------|
| `OrderBook` | keep | `ArrayOrderBook` is planned (correctness ref vs hot path impl) |
| `OrderBookReader` / `Writer` | keep | capability narrowing — read-only market data consumers |
| `Matcher` | keep | price-time vs pro-rata is a real venue-level variation |
| `PriceLevel` | keep, watch | one impl; a flat-array variant is plausible. Hold at ≤2 impls |
| `BookSide` | keep, watch | same. Do not let per-order-type impls appear (OOD-8) |
| `TradeSink` / `DepthSink` | keep | genuine strategy — collecting vs publishing vs counting |

The "watch" entries carry a rule: **at most two implementations of any hot-path
interface.** Beyond two, convert to data plus a `switch` per OOD-8.

**Enforcement.** Review. The question to ask of a new interface: *name the second
implementation.* If you can't, write the class.

**Status.** `partial` — every abstraction still earns its place, and `MatchingEngine` now takes a
`Matcher` rather than hardwiring one, which is what made the boundary testable without a working
walk. Stays `partial` because the ≤2-implementations rule is review-only.

---

## OOD-18 — Indirection is provisional

**Rule.** Today's object references and `TreeMap` lookups are an implementation choice with
a known successor: flat arrays indexed by `int`. Write core code so that migration is
mechanical — no logic that depends on object identity, GC reachability, or reference
nullability where an index sentinel would do.

**Why.** The 20M ops/sec target is not reachable through a `TreeMap<Long, PriceLevel>`:
every node is a separate heap object, so a walk down price levels is a chain of cache
misses, and `Long` keys mean boxing. The endgame is a price-indexed flat array (a "ladder")
with orders in a slab, `int` indices instead of references, and no per-order allocation at
all. That rewrite is tractable *if* the surrounding code treats indirection as an
implementation detail, and a rewrite of the whole engine if it doesn't.

**Here.** Concrete forward-compatibility rules:

* `Order` as an intrusive node is already the right shape: `Order next/prev` becomes
  `int nextIdx/prevIdx` into a slab, and the linking logic is unchanged in structure.
* Never use an order's object identity (`==` between orders, `IdentityHashMap`) as
  meaningful. Identity is `orderId`.
* Keep `PriceLevel` and the order node as **separate types** (`ENGINEERING_GUIDE.md`'s
  structural rule). One type doing both jobs is how state-corruption bugs start, and it
  also blocks the ladder migration, where levels are array slots and orders are slab rows.
* Prefer index/count returns over collection returns (already implied by OOD-9).

**Enforcement.** Benchmarks (NFR-2.x) are what will eventually *force* this migration;
until then, review. The `OrderBook` interface existing with two planned implementations is
what makes the migration a parallel-implementation exercise rather than a rewrite — and the
`TreeMap` version stays forever as the correctness oracle to differential-test against.

**Status.** `aspirational` by definition — this one is about not foreclosing a future.

---

## Applying this document

**When adding code**, the questions in order: Which zone — core or edge (OOD-3)? Who owns
the invariant this touches (OOD-1)? Does it allocate on the hot path (OOD-11)? Is variation
data or subtype (OOD-8)? Can the compiler enforce the constraint instead of a comment?

**When a principle is violated**, either fix the code or **change this document** and say
why. A principle nobody follows is worse than no principle, because it teaches readers that
the documented design is fiction. `partial` and `aspirational` above are honest labels for
work queued, not permanent excuses — each one has a requirement ID and a branch.

**Enforcement, as it now stands.** `ArchitectureTest` holds 13 rules:

| Rule | Principle | Requirement |
|------|-----------|-------------|
| Core depends only on itself and the JDK | — | NFR-5.1 |
| No public method returns `List`/`Map`/`Set`/`Collection` | OOD-9 | API-11.1 |
| No `java.util.stream` in `book..`/`matching..` | OOD-9 | NFR-5.1 |
| No `java.util.concurrent`/`java.lang.ref` in core | OOD-2 | NFR-4.1 |
| DTO packages contain only records, enums, interfaces | OOD-4 | FR-5.5 |
| No `Double`/`Float`/`BigDecimal` dependency | OOD-12 | NFR-5.1 |
| No `double`/`float` fields | OOD-12 | NFR-5.1 |
| No clock, `Random`, `UUID` or `Math` in core | OOD-13 | NFR-1.1 |
| `matching..` does not depend on edge DTOs | OOD-3 | NFR-5.1 |
| Only the boundary depends on `validation..` | OOD-5 | API-8.1 |

Plus two things ArchUnit cannot express, held by ordinary tests: **sealedness in both directions**
(`SealingTest`, via `Class.isSealed()`), and **entity mutators uncallable outside `book..`**, which
needs no test at all — package-private means a violation does not compile.

**What is deliberately still open.** `Iterator` is not banned as a return type (it is `Iterable`'s
contract, and a rule bent around one class is worse than the narrower true rule). Arrays are not
banned either — ArchUnit expresses it awkwardly and the real defence is review. And the allocation
budget (OOD-11) is unmeasured until JMH lands, which is why it is the one principle still labelled
`partial` on grounds of evidence rather than design.
