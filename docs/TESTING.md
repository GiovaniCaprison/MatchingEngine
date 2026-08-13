# Matching Engine — Testing

The TDD roadmap and the test-suite reference, in one place. Companion to
`ENGINEERING_GUIDE.md` (model, algorithm, benchmarking, profiling).

---

## Current state

The full layered test suite exists and `mvn test` is **GREEN — vacuously**. Example
bodies are empty (each carries a `// TODO` naming its requirement), the golden factory
discovers fixtures and skips them until the engine is wired, ArchUnit rules pass with no
production classes, and jqwik properties run with no assertion. The build, the package
layout, the coverage matrix, and the cost lanes are all in place.

What does **not** exist yet: the domain types, the engine API, and the matching logic.
The remaining work is to make those real and turn empty bodies into red→green cycles.

---

## The core principle: infrastructure → correctness → measurement

You cannot test what doesn't compile, you cannot trust a fix you can't run red first, and
you cannot profile a matcher that doesn't match. The step order is fixed:

```
Step 0  Build + layout            → done: mvn test runs the suite
Step 1  Domain types + API        → tests have something to call (compiles)
Step 2  First RED test            → prove a real behavior is missing
Step 3  Minimal explicit surface  → the atomic "yes, full-stop" tests
Step 4  Matching core (red→green) → you write the algorithm
Step 5  Golden harness            → done (scaffold): fill fixtures
Step 6  Property + structural      → done (scaffold): fill assertions
Step 7  JMH                       → the performance numbers (much later)
```

**The gate:** once the domain + API compile and you have one test failing for a *real*
reason, you're clear to write the engine. Everything before that is setup; everything
after is the project.

---

## The TDD structure: organise by LAYER, trace by ID

Requirement families (FR/VR/NFR/API) and the test *mechanism* (example, golden, property,
structural, benchmark) are two different axes. A single FR needs different mechanisms
(FR-3.1 is golden, FR-5.3 is a property, FR-5.5 is structural), so you can't file by both
at once.

The deciding question for *which mechanism* is **the oracle**: can you state the expected
output as a literal, without computing it the way the system does?

* If yes, and the output is small → **explicit example**.
* If yes, but the output is rich (trade streams, full book) → **golden / snapshot**.
* If no — the only expectation is an invariant or "same as a reference run" → **property**.
* If it's not a behavior at all (structure, dependencies) → **structural**.
* If it's a number (throughput, latency) → **benchmark** (JMH, not JUnit).

The resolution used here:

* **Physical files follow the LAYER** — the mechanism dictates the tooling, the runner,
  and the cost lane. A jqwik `@Property`, an ArchUnit `@ArchTest`, and a JUnit `@Test`
  cannot live cleanly in one class.
* **Requirement identity rides on every test** via `@DisplayName("FR-3.1: …")` (humans)
  and `@Requirement("FR-3.1")` (machine-readable, for the coverage matrix).

You keep both views: browse by layer in the tree, report/filter by ID via the annotation.

| Layer | Job | Tooling |
|---|---|---|
| **Explicit example** | Atomic, small output, 1:1 to a requirement; intent-revealing; *existence* proof | JUnit + AssertJ |
| **Golden / snapshot** | Explicit but *rich* deterministic output; tedious to hand-assert; spans requirements | JUnit `@TestFactory` + fixtures |
| **Property-based** | No literal oracle — invariants over random streams; shrinks failures to a minimal case | jqwik |
| **Structural** | Non-behavioral architecture rules | ArchUnit |
| **Benchmark** | Throughput / latency numbers | JMH (separate from JUnit) |

Two rules that keep the layers honest:

* An explicit example proves the requirement **for that example** — existence, not
  universality. The property layer covers "everywhere." They stack; they don't substitute.
* Golden tests are for **rich** output. Do **not** point them at the atomic validation
  surface — a snapshot records what the output *is*, not what the requirement *means*, and
  it localizes failures poorly. Keep VR-1/VR-2/API-9 as hand assertions that say the
  requirement out loud.

---

## Directory layout

```
src/test/java/com/imc/me/
  support/     Requirement, TestTags, Orders (builders), ScenarioRunner, BookInvariants
  explicit/    EXAMPLE layer — atomic, one requirement per test, hand assertions
  golden/      SNAPSHOT layer — rich deterministic output diffed vs fixtures
  property/    jqwik — invariants over random streams (STRESS lane)
  structural/  ArchUnit — architecture rules (dependency-free core, no leaks)
  coverage/    CoverageMatrixTest — traceability guard
src/test/resources/scenarios/        golden fixture pairs (*.input / *.expected)
src/test/resources/requirements.txt  canonical spec inventory (source of truth)
benchmarks/    JMH lives here later (Step 7) — not JUnit
```

## Requirement → layer → file map

| Layer | File(s) | Requirement IDs |
|---|---|---|
| Explicit | `OrderLifecycleTest` | FR-1.1, FR-1.2, FR-1.3, FR-2.1, FR-2.2, FR-4.1, FR-4.2, FR-4.3 |
| Explicit | `ValidationTest` | VR-1.1, VR-2.1, VR-2.2, VR-3.1, VR-3.2, API-8.1, API-8.2 |
| Explicit | `QueryTest` | FR-5.1, FR-5.2, FR-5.4, API-4.1, API-5.1, API-6.1 |
| Explicit | `OutcomeAndEventTest` | API-1.1, API-1.2, API-1.3, API-2.1, API-9.1, API-10.1, API-7.1 |
| Golden | `GoldenScenarioTest` (+ fixtures) | FR-3.1..3.5, FR-2.3, FR-2.4, FR-2.5, FR-2.6, FR-4.4, FR-4.5, API-3.1, VR-4.1, VR-4.2, NFR-1.1, NFR-1.2, FR-6.1 |
| Property | `BookInvariantPropertyTest` | NFR-3.1, NFR-3.2, VR-6.1, NFR-6.1, FR-5.3 |
| Structural | `ArchitectureTest` | NFR-5.1, API-11.1, FR-5.5, NFR-4.1 |
| Benchmark | `benchmarks/` (JMH, deferred) | NFR-2.1, NFR-2.2, NFR-2.3 |

**Trimmed (do not test as written):** FR-1.4 (dup of NFR-1 — give determinism one home,
the golden/NFR-1 tests), FR-6.2 (untestable as phrased — a property of a hypothetical
consumer; make it concrete as "the event stream reconstructs final book state" or drop it),
API-10.2 (docs, not behavior — put it in the README), VR-5.1 (undecided self-trade policy —
decide first, the test follows).

**Two placement notes** (where implementation refined the earlier plan):
* **API-8.1/8.2** are tested *behaviorally* in the explicit layer (invalid in → typed
  rejection + zero state change), which is how you actually observe "validation at the
  boundary." An ArchUnit reinforcement is a TODO.
* **NFR-6.2** ("invariants are assertable") is not its own test — it's the capability that
  `BookInvariants` + the property layer consume.

---

## Cost lanes (JUnit tags)

| Tag | Where | Run with |
|---|---|---|
| `fast` | explicit, structural, coverage | `mvn test` (default) |
| `golden` | golden | `mvn test` (default) |
| `stress` | property (jqwik) | `mvn test -Pstress` |

Default `mvn test` excludes `stress` so the inner loop stays fast; CI runs `-Pstress`
pre-merge/nightly.

---

## Coverage matrix (traceability guard)

`coverage/CoverageMatrixTest` closes the loop between the spec and the tests. It reads the
canonical inventory `requirements.txt` and scans the classpath (via ClassGraph) for every
`@Requirement`, then enforces:

1. **everyRequirementHasATest** — a claimable requirement (explicit/golden/property/
   structural) with no test FAILS the build.
2. **noTrimmedRequirementIsTested** — a deliberately-cut ID that gains a test FAILS (regression guard).
3. **noUnknownRequirementClaimed** — a test claiming an ID absent from the inventory FAILS (typo/orphan guard).

It writes `target/coverage-matrix.md` on every run. "Coverage" means a test *claims* the
requirement, not that its body is implemented — correct for a TDD blank slate, and why your
red→green discipline (not the matrix) is what proves behavior.

How IDs are claimed per layer:
* **explicit** — `@Requirement("FR-1.1")` on each method.
* **golden / property / structural** — one class-level `@Requirement({...})` listing every
  ID that class owns.
* **benchmark** (NFR-2.x) — listed as `benchmark` in the inventory; deferred to JMH, reported
  but not required to have a JUnit test.

ClassGraph reads class files on the classpath regardless of which tests *execute*, so even
though the property layer is `stress`-tagged and skipped by default, the matrix still sees
its claimed IDs. To add a requirement: add a line to `requirements.txt` and a test that
claims it — the build stays RED until both exist.

---

## Step 1 — Domain types + API surface

Several requirements *dictate* these shapes, so this is design, not boilerplate.

**Value types (immutable → `record`):**
```java
public enum Side { BUY, SELL }
public enum OrderType { LIMIT, MARKET, IOC, FOK, POST }

// Reference data. Needed even for one symbol — VR-2.2 (tick/precision) can't be
// validated without it. Prices are scaled longs, never double/float.
public record Instrument(int symbolId, String ticker,
                         long tickSize, long lotSize, int priceScale) {}

public record Trade(long aggressorId, long restingId, long price, long qty) {}  // FR-3.4
```

**Typed outcomes (sealed → exhaustive, no nulls, no booleans):** this one decision satisfies
API-1.1, API-1.2, API-9.1, FR-1.3, and API-2.1 at once, and makes "not found" a *type*, not an NPE.
```java
public sealed interface SubmitResult permits Accepted, Rejected {}
public record Accepted(long orderId, List<Trade> fills) implements SubmitResult {}
public record Rejected(RejectReason reason) implements SubmitResult {}

public sealed interface CancelResult permits Cancelled, NotFound {}
public enum RejectReason { NON_POSITIVE_QTY, NON_POSITIVE_PRICE, TICK_VIOLATION, WOULD_CROSS }
```

**Entity (mutable lifecycle → class, identity is a primitive `long`):**
```java
public final class Order {
    final long orderId;        // assigned by the sequencer, NOT self-generated
    final long clientOrderId;
    final int  symbolId;
    final Side side;
    final OrderType type;
    final long price;          // scaled long
    long remainingQty;         // shrinks on partial fill — this is why it's an entity
    Order prev, next;          // intrusive list pointers (used by PriceLevel later)
}
```

**Public API** (the observable surface tests assert against — designing for testability and
a clean API are the same task):
```java
public final class MatchingEngine {
    public SubmitResult submit(NewOrder cmd);     // FR-1, API-1
    public CancelResult cancel(long orderId);     // FR-4.1, API-2
    public AmendResult  amend(AmendOrder cmd);    // FR-4.3, API-3
    public TopOfBook    topOfBook(Side side);     // FR-5.1, API-4
    public Depth        depth(Side side);         // FR-5.3, API-5
    public OrderStatus  status(long orderId);     // FR-5.4, API-6
    // event stream for FR-6 / API-7 — a consumer registers, never polls internals
}
```

UID assignment lives in the sequencer (the single writer), **not** on `Order` — this keeps
determinism (NFR-1) and the future LMAX single-writer model intact. Compile before moving on.

---

## Step 2 — The first RED test (the gate)

Prove the harness can fail for a real reason before you trust any green. Pick the most
central behavior — an aggressor crossing resting liquidity (FR-3.3):

```java
@Test
@Requirement("FR-3.3")
@DisplayName("FR-3.3: an aggressing order matches resting liquidity")
void crossingBuyMatchesRestingSell() {
    var engine = new MatchingEngine(instrument);
    engine.submit(Orders.restingSell(100, 10));

    var result = engine.submit(Orders.aggressingBuy(100, 10));

    assertThat(result).isInstanceOf(Accepted.class);
    assertThat(((Accepted) result).fills()).singleElement()
        .satisfies(t -> { assertThat(t.qty()).isEqualTo(10);
                          assertThat(t.price()).isEqualTo(100); });
    assertThat(engine.topOfBook(Side.SELL).isEmpty()).isTrue();   // book cleared
}
```

It fails (no matching logic yet). **That red is the green light to write the engine.**

---

## Step 3 — The minimal explicit surface

The "yes, this does this, full-stop" layer — small, unambiguous, one requirement per test,
intent-revealing, highest clarity-per-line in the suite. Fill these with hand-written AssertJ:

* **VR-1.1** zero/negative qty → `Rejected(NON_POSITIVE_QTY)`
* **VR-2.1** non-positive limit price → `Rejected(NON_POSITIVE_PRICE)`
* **VR-2.2** off-tick / over-precision price → `Rejected(TICK_VIOLATION)` (parameterize over a small table)
* **API-2.1 / FR-4.2** cancel unknown UID → `NotFound`, never an exception
* **FR-1.3** accepted order carries a returned UID
* **FR-5.2** empty side is clearly indicated by `topOfBook`
* **FR-2.2** a fully-unmatched market order does not rest

Use `@ParameterizedTest` (`@EnumSource(OrderType.class)`, `@CsvSource`) wherever a requirement
is really a small grid rather than a single case.

---

## Step 4 — The matching core

You write this — it's the point of the project. Drive it with the Step 2 test, then add
behaviors one red→green cycle at a time. The full algorithm, data structures, and the
PriceLevel/node split are in `ENGINEERING_GUIDE.md` ("The matching algorithm"). Build order,
each step gated by a test: exact-price match → best-price sweep → trades → per-type remainder.

---

## Step 5 — The golden harness

The engine's rich output — an *ordered* trade stream plus the resulting book — is the single
best snapshot target in the suite. Hand-asserting a twelve-trade sweep is itself where bugs
hide; a blessed fixture you eyeball once is clearer and more thorough. This layer covers
FR-3.x, the order-type behaviors, VR-4.x, amend-priority (FR-4.4/4.5), and *is* the
determinism tests (NFR-1.1/1.2) by construction.

**Fixture naming = traceability:** encode the requirement ID in the filename, e.g.
`fr_3_1_price_priority_sweep.input` → FR-3.1. The `@TestFactory` discovers it automatically;
no code change to add a scenario.

```
src/test/resources/scenarios/
  fr_3_1_price_priority_sweep.input        # a sequence of commands
  fr_3_1_price_priority_sweep.expected     # blessed trades + final book
```

Keep `.expected` plain text so diffs are reviewable. When output changes legitimately,
regenerate the file but **review the diff** before committing — exactly like a Jest snapshot.
Blind re-blessing bakes in bugs.

---

## Step 6 — Property and structural layers

**Property (jqwik)** — for invariants with no literal oracle. jqwik shrinks a failure to its
minimal reproducing sequence, which is gold for a matcher:
* **NFR-3.1 / VR-6.1 / NFR-6.1** — after any random valid stream, aggregate depth at each
  level equals the sum of resting quantities.
* **NFR-3.2** — no resting orders leak after a full randomized run.

**Structural (ArchUnit)** — for non-behavioral rules:
* **NFR-5.1** — core package depends only on `com.imc.me..` and `java..`.
* **API-11.1** — no public method returns a mutable collection / leaks internals.

All three (jqwik, ArchUnit, AssertJ) are `<scope>test</scope>` — they never touch the
runtime classpath, so they do not violate NFR-5; the ArchUnit rule above is what *proves* it.

---

## Step 7 — JMH (defer until the engine is correct)

The performance requirements are **not** JUnit tests. Wall-clock assertions inside the unit
suite are the flakiest thing you can write (JIT, GC, CI noise) and contradict the project's
own measurement discipline. Split the claim from the number:

* **The number** (ops/sec, p50/p99/p99.9, SG-4) → JMH in a clean forked JVM with warmup and
  `Blackhole`. Setup, annotations, and coordinated-omission guidance are in
  `ENGINEERING_GUIDE.md` ("Benchmarking").
* **The asymptotic guarantee** (NFR-2.1 sub-linear submit, NFR-2.2 O(1) cancel, NFR-2.3 O(1)
  top-of-book) → if you want it in JUnit, **count operations** (node visits / comparator
  calls) and assert the count doesn't grow with book size. Deterministic, not timing-based —
  this is how you express "sub-linear" without a stopwatch.

Do this only after Steps 4–6. A fast wrong answer is worthless.

---

## First-week order

1. Step 1 — domain types + `MatchingEngine` API compile.
2. Step 2 — one failing FR-3.3 test (the gate). **You may now start the engine.**
3. Step 3 — the minimal explicit surface (VR-1, VR-2, API outcomes).
4. Step 4 — matching core, red→green: exact match → best-price sweep → trades → order types.
5. Steps 5–6 — fill golden fixtures, then property + structural assertions.
6. Step 7 — JMH, only once it actually matches.

Don't skip ahead to JMH, and don't write a green test before you've seen it red.
