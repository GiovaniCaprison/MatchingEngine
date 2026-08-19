# Matching Engine: Engineering Guide

The model, the algorithm, and the measurement road (benchmarking + profiling).
For the test scaffolding and TDD roadmap, see `TESTING.md`.

Ordering principle for the whole project: infrastructure -> correctness -> measurement.
You can't profile a matcher that doesn't match, and you can't trust a fix you can't
test. Build the skeleton, get it matching correctly under test, then measure.

---

## Mental model

A single-symbol matching engine at runtime is three structures:

* Two sorted books (bids, asks) of `price -> PriceLevel`, a `TreeMap` to start.
* A FIFO doubly-linked list of orders inside each `PriceLevel` (price-time priority).
* A `uid -> node` map for O(1) cancel/amend by id.

One concept shapes everything else: a production matching engine is single-threaded
per book. The reason is speed rather than simplicity: a deterministic, lock-free,
cache-resident single writer beats coordinating threads on shared state, which is the
LMAX Disruptor insight. Build that single-writer discipline *deliberately* from the
start: one writer mutates the book, ids are minted at one ordered point (the sequencer),
and replay of the same input is bit-for-bit reproducible. This is what makes determinism
(NFR-1) free rather than retrofitted.

A second decision that ripples everywhere: prices are scaled `long`s, never
`double`/`float`. `100.25` becomes `1002500` at a fixed scale; you convert at the I/O
edge using the instrument's `priceScale`. Floating point can't represent decimal prices
exactly and will cost you equality/correctness bugs long before it costs you latency.

---

## Project layout

Java's hard rule: the folder path under the source root equals the package name with
dots -> slashes. A class declaring `package com.imc.me.book;` must live at
`src/main/java/com/imc/me/book/`. Maven expects `src/main/java` for code and
`src/test/java` for tests.

```
matching-engine/
├── pom.xml                         # deps, JDK release, plugins (shade/jmh later)
├── .gitignore                      # /target, *.iml.idea/, etc.
├── README.md
├── docs/
│   └── nfr-fr.md                   # the NFR/FR spec, in-repo
│
├── src/main/java/com/imc/me/       # root package; "me" = matching engine
│   ├── MatchingEngine.java         # entry point + public API surface
│   │
│   ├── domain/                     # core value types, the vocabulary of the system
│   │   ├── Order.java              # entity (mutable lifecycle), id is a long
│   │   ├── Side.java               # enum BUY/SELL
│   │   ├── OrderType.java          # enum LIMIT/MARKET/IOC/FOK/POST
│   │   ├── Trade.java              # record
│   │   └── Instrument.java         # record: tick/lot/scale reference data
│   │
│   ├── book/                       # the order book data structure(s)
│   │   ├── OrderBook.java          # interface
│   │   ├── TreeMapOrderBook.java   # reference impl (correctness)
│   │   ├── ArrayOrderBook.java     # cache-friendly impl (later, for the hot path)
│   │   ├── PriceLevel.java         # owns head/tail + running totalQty
│   │   └── BookSide.java
│   │
│   ├── matching/                   # the matching algorithm, your hot path
│   │   ├── Matcher.java            # interface
│   │   ├── PriceTimeMatcher.java   # price-time priority impl
│   │   └── MatchResult.java
│   │
│   ├── event/                      # inbound commands + outbound events
│   │   ├── command/                # NewOrder, CancelOrder, AmendOrder
│   │   └── outbound/               # OrderAccepted, OrderRejected, TradeExecuted
│   │
│   ├── sequencer/                  # ordering/ingress, single-writer, mints uids
│   │   └── Sequencer.java          # (where a Disruptor ring buffer lands later)
│   │
│   ├── gateway/                    # I/O boundary: wire -> command, event -> wire
│   ├── config/                     # startup config, instrument/symbol setup
│   └── util/                       # tiny, dependency-free helpers only
│
└── (later) benchmarks/             # JMH lives here (Step 7), not the engine classpath
```

The test tree (`src/test/...`) is organised by test *layer*, not by these packages, see `TESTING.md`.

`pom.xml` essentials: `maven.compiler.release` = 21, `UTF-8` source encoding, and a
`groupId/artifactId/version` triple (`com.imc` / `matching-engine` / `0.1.0-SNAPSHOT`).
Every test dependency is `<scope>test</scope>`, which keeps them off the runtime classpath
and *enforces* NFR-5 ("no external deps for the core engine"). Verify with `mvn compile`.

> Gradle alternative: `build.gradle` with the `java` plugin, JUnit under
> `testImplementation`, same `src/main/java` layout. Only the command changes
> (`./gradlew` vs `mvn`). The build tool makes zero difference to runtime latency.

---

## The matching algorithm

This is the engine, write it yourself; that's the point. Drive it with the tests in
`TESTING.md` (red -> green, one behavior at a time). Algorithm for an incoming buy
(sell is the mirror):

1. Walk the opposing book from the best price inward. For a buy, best ask = lowest
   sell price = `askBook.firstEntry()`. Loop while an opposing level exists and the
   prices cross (`incomingBuyPrice >= bestAskPrice`). A market buy (price `MAX_VALUE`)
   naturally crosses every level until liquidity runs out, so it needs no special case (FR-3.1).
2. Within a level, match FIFO from the first real order. Consume
   `min(incoming.remaining, resting.remaining)` each step. Emit a trade at the resting
   order's price, because price improvement accrues to the aggressor (FR-3.5). The trade record
   (both uids, qty, price) is the engine's real output (FR-3.4, FR-6.1).
3. Maintain invariants as you go:
   * Resting order fully filled -> unlink the node *and* `uidMap.remove(uid)`.
   * Level's last order gone -> remove the level key from the `TreeMap`.
   * Incoming order exhausted -> stop.
4. After the walk, apply the per-type remainder policy:
   * LIMIT remainder -> rest it (`uidMap.put` on *every* insert).
   * MARKET remainder -> cancel; never rests (FR-2.2).
   * IOC -> cancel remainder (FR-2.4). FOK -> check full fillability *before*
     matching, all-or-nothing (FR-2.5). POST -> reject if it would cross (FR-2.6).
5. Cancel is safe by type: `uidMap.get(uid)` may be absent -> return a typed
   "not found" outcome, never an NPE (FR-4.2).

Structural rule that prevents a whole class of bugs: keep the level container and the
order node as *separate types*. `PriceLevel` owns `head`, `tail`, and a running `totalQty`;
the order node owns `next`, `prev`, and its `Order`. One type doing both jobs is how
state-corruption bugs creep in. Bonus: `PriceLevel.totalQty` gives FR-5.3 depth for
free and makes the VR-6.1 invariant a one-line assertion.

Amend priority (FR-4.4 / FR-4.5): a qty *decrease* mutates the node in place and keeps
time priority; a qty *increase* or *reprice* must unlink and re-append to the tail (loses
priority). This is a concrete constraint on the node design, and it's why amend-priority is
tested as a scenario, not a one-line example (see `TESTING.md`).

Build incrementally: exact-price match working + tested -> best-price walk -> trades -> each
order type. Let the tests catch each step.

---

## Benchmarking (JMH)

Why JMH, not a `nanoTime()` loop: the JVM is a moving target. (a) The JIT optimizes
*while running*, the first ~10k iterations are slow then suddenly fast, so you must warm
up. (b) The JIT *deletes* code whose result you don't use (dead-code elimination), so a
naive benchmark can measure nothing while reporting a number. (c) GC + scheduling add noise.
JMH (from the JDK team) forks fresh JVMs, runs warmup, and gives you `Blackhole` to consume
results so the JIT can't elide them.

How it works mechanically:
* Annotate a method with `@Benchmark`. JMH's annotation processor generates the harness
  (warmup loops, timing, Blackhole plumbing) into `target/generated-sources` at compile time.
* Canonical run model is an uber-jar: `mvn package` shades benchmarks + JMH runtime into
  `benchmarks.jar`; run with `java -jar benchmarks.jar`. Running in a clean JVM (not inside
  Maven) is *why* the numbers are trustworthy.

Setup: add `jmh-core` + `jmh-generator-annprocess`, plus `maven-shade-plugin` producing
`benchmarks.jar` with main class `org.openjdk.jmh.Main`. This is exactly what the official
`jmh-java-benchmark-archetype` generates, read its `pom.xml` as a reference. Keep benchmarks
in a separate module/source set so JMH deps never reach the engine classpath.

Annotations that matter:
* `@BenchmarkMode(Mode.Throughput)`, ops/sec (your SG-4 number). `Mode.SampleTime`, a
  latency *distribution* (percentiles), which for an exchange matters far more than the average.
* `@Warmup(iterations=5)` / `@Measurement(iterations=10)` / `@Fork(2)`, discard JIT warmup,
  then measure across fresh JVMs.
* `@State(Scope.Benchmark)`, holds a pre-built book so you measure *matching*, not setup.

Reading results: report p50 / p99 / p99.9 / max latency and never the mean, because the tail is
what kills you. Learn coordinated omission: if the benchmark pauses (GC) then "catches up,"
it silently under-counts the worst latencies, making a bad engine look good. JMH's `SampleTime`
mode + the HdrHistogram library expose this honestly. This is the #1 way latency benchmarks lie.

Defer all of this until the engine is correct (TESTING.md Step 7). A fast wrong answer is worthless.

---

## Profiling

Benchmarks say *how fast*; profilers say *why*. On the road to millions of ops/sec the enemy
is usually allocation and GC pauses, not CPU.

1. JFR (Java Flight Recorder), in JDK 21 already, zero install. Run with
   `-XX:StartFlightRecording=filename=run.jfr`, open in JDK Mission Control: CPU hot methods,
   allocation-per-type, GC timeline. Start here.
2. async-profiler, industry-standard JVM flamegraphs, lower overhead than JFR for CPU.
   Attach to a running JVM:
   * `asprof -e cpu -d 30 -f cpu.html <pid>`, 30s CPU flamegraph.
   * `asprof -e alloc -d 30 -f alloc.html <pid>`, allocation flamegraph (the important one:
     shows which objects the hot path creates, the input to "zero-allocation hot path" work,
     the real justification for future off-heap / FFM work). Output is HTML; view in a browser.
3. GC visibility: `-Xlog:gc*:file=gc.log`. For an exchange a single 50ms pause is a
   catastrophe, and this log is how you would notice.

The throughline: JMH says "it got slower," async-profiler's alloc view says "because this
method now allocates a `Long` per match," -> you know what to fix. That loop is what justifies
each future rewrite: you will have *numbers* proving the step helped.

---

## Build order

1. Skeleton + `pom.xml` -> `mvn compile` green (this guide).
2. Domain types + `MatchingEngine` API compile (TESTING.md Step 1).
3. One failing crossed-book test, then drive the matching core green (TESTING.md Steps 2-4).
4. Golden + property + structural layers (TESTING.md Steps 5-6).
5. JMH + profiling, only once the engine actually matches.
