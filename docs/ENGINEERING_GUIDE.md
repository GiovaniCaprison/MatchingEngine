# Engineering Guide

The model, the algorithm, and the measurement road. `REQUIREMENTS.md` is the specification,
`OOD_PRINCIPLES.md` is the design rationale, and `TESTING.md` is how any of it gets proved.

The ordering principle for the whole project is infrastructure, then correctness, then
measurement. You cannot profile a matcher that does not match, and a fast wrong answer is
worthless.

## Mental model

A single-symbol matching engine at runtime is three structures:

- two sorted sides, bids and asks, mapping price to a price level, a `TreeMap` to start
- a FIFO doubly-linked list of orders inside each level, giving price-time priority
- a map from uid to order, for constant-time cancel and amend

One decision shapes everything else: a production engine is single-threaded per book, for speed
rather than for simplicity. A deterministic, lock-free, cache-resident single writer beats
threads coordinating on shared state, which is the LMAX Disruptor insight. Building that
discipline in from the start is what makes determinism free rather than retrofitted. One writer
mutates the book, ids are minted at one ordered point, and replaying the same input is
reproducible bit for bit.

A second decision that ripples everywhere: prices are scaled longs. `100.25` at scale 4 becomes
`1002500`, converted at the I/O edge using the instrument's price scale. Floating point cannot
represent decimal prices exactly and will cost correctness long before it costs latency.

## Layout

```
src/main/java/com/imc/me/
  MatchingEngine.java     the public API and the validation boundary
  domain/                 Instrument, OrderSide, OrderType, OrderView, Trade
  book/                   OrderBook, BookSide, PriceLevel and their TreeMap and
                          linked-list implementations, plus the Order entity
  matching/               Matcher, PriceTimeMatcher, TradeSink
  registry/               OrderRegistry, session-lifetime order state
  sequencer/              Sequencer, the single source of ids and sequence numbers
  validation/             OrderValidator
  event/command/          inbound commands
  event/dto/              Depth, OrderStatus, TopOfBook
  event/result/           the sealed submit, cancel and amend outcomes
  event/sink/             collecting sinks and the outbound listener
  util/                   Prices, Seq

src/test/java/com/imc/me/
  boundary/               tests restricted to the public API by the compiler
  book/                   tests that need to see package-private links
  golden/                 the scenario corpus
  support/                harness code
src/test/resources/scenarios/
```

The order entity lives in `book` rather than `domain` because it is the book's intrusive list
node, and that is what allows its mutators to be package-private. See OOD-1 and OOD-4.

Every test dependency is test-scope, which keeps it off the runtime classpath and is what makes
the dependency-free core (NFR-5.1) true rather than aspirational.

## The matching algorithm

For an incoming buy; sell is the mirror.

1. Walk the opposing side from the best price inward. For a buy the best ask is the lowest sell
   price. Continue while an opposing level exists and the prices cross. A market buy carries a
   price sentinel so it crosses every level until liquidity runs out, which is why the walk needs
   no special case for it (FR-3.1).
2. Within a level, match FIFO from the first order, consuming the minimum of the two remaining
   quantities each step. Emit the trade at the resting order's price, since price improvement
   accrues to the aggressor (FR-3.5). The trade record carrying both uids, quantity and price is
   the engine's real output (FR-3.4).
3. Maintain the invariants as you go. A fully filled resting order is unlinked and removed from
   the uid map. A level whose last order is gone is removed from the side. An exhausted aggressor
   stops the walk.
4. Apply the per-type remainder policy. A limit remainder rests. A market or IOC remainder is
   cancelled. FOK is decided before the walk by a fillability probe, and POST is rejected before
   the walk if it would cross.
5. Cancel is safe by type: a uid lookup may find nothing, which returns a typed not-found outcome
   rather than throwing (FR-4.2).

Two structural rules that prevent whole bug classes. Keep the level container and the order node
as separate types: the level owns head, tail and a running total, and the order owns its links.
One type doing both jobs is how state corruption starts, and it blocks the eventual migration to a
flat ladder. And detach a node on removal, clearing both links, because a half-detached node is
the closest thing Java has to a use-after-free.

Amend priority is a constraint on the node design. A quantity decrease mutates in place and keeps
time priority (FR-4.5). A quantity increase or a reprice unlinks and re-appends at the tail, losing
priority (FR-4.4).

Build order: exact-price match, then the best-price walk, then trades, then each order type.

## Benchmarking

Not a `nanoTime` loop, because the JVM is a moving target. The JIT optimises while running, so
the first several thousand iterations are unrepresentative. It also deletes code whose result is
unused, so a naive benchmark can measure nothing while reporting a number. GC and scheduling add
noise on top. JMH forks fresh JVMs, runs warmup, and provides `Blackhole` to consume results so
the JIT cannot elide them.

Mechanically: annotate a method with `@Benchmark` and JMH's annotation processor generates the
harness at compile time. The canonical run model is an uber-jar, shaded with JMH's runtime and run
as `java -jar benchmarks.jar`, in a clean JVM outside Maven, which is why the numbers are
trustworthy. Keep benchmarks in a separate source set so JMH never reaches the engine classpath.

The annotations that matter: `Mode.SampleTime` for a latency distribution, `Mode.Throughput` for
ops per second, `@Warmup` and `@Measurement` and `@Fork` to discard warmup and measure across
fresh JVMs, and `@State(Scope.Benchmark)` holding a pre-built book so you measure matching rather
than setup.

Report p50, p99, p99.9 and max, never the mean, because the tail is what kills you. Learn
coordinated omission: a benchmark that pauses and then catches up under-counts its worst
latencies and makes a bad engine look good. `SampleTime` with HdrHistogram exposes it honestly.
This is the most common way a latency benchmark lies.

## Profiling

Benchmarks say how fast, profilers say why. On the road to millions of operations per second the
enemy is usually allocation and GC pauses rather than CPU.

1. JFR is in the JDK already. Run with `-XX:StartFlightRecording=filename=run.jfr` and open it in
   JDK Mission Control for hot methods, allocation per type and a GC timeline. Start here.
2. async-profiler gives lower-overhead flamegraphs. `asprof -e cpu -d 30 -f cpu.html <pid>` for
   CPU, and `asprof -e alloc -d 30 -f alloc.html <pid>` for allocation, which is the important one:
   it shows which objects the hot path creates, and it is the input to any zero-allocation work.
3. `-Xlog:gc*:file=gc.log` for GC visibility. For an exchange a single 50ms pause is a
   catastrophe, and this log is how you would notice.

The loop that justifies each rewrite: JMH says it got slower, the allocation flamegraph says a
method now allocates per match, and you know what to fix. Each step comes with a number proving it
helped.
