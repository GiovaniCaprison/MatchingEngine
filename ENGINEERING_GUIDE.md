# OrderBook — Engineering & Roadmap Guide

A working reference for building this matching engine into something real: the
phases, **why** each exists, and **how** to do it yourself. This is deliberately
a guide, not a set of changes already made — the understanding is the point.

> **Context for this guide (your actual environment, verified):**
> - macOS on Apple M4 Max, JDK 21 (Temurin), Maven 3.9 installed, Gradle not installed.
> - NvChad already has the Java toolchain: `jdtls` (language server) + `nvim-jdtls`
>   (the plugin) + `google-java-format`, all via Mason. Nothing to install for the editor.
> - **JFR** ships with JDK 21 (zero-install profiling). **async-profiler** is not yet installed.
> - `brew` is available for installs.

---

## Where the engine stands today (the honest baseline)

The **data structures are the textbook design** — keep them:
- Two sorted `TreeMap`s of price → price-level (bids, asks). Sorted so matching starts
  at the *best* price; `firstEntry()` / `lastEntry()` are O(log n).
- A FIFO doubly-linked list at each price level (price–**time** priority).
- A `uid → node` `HashMap` so cancel/amend is O(1).

The **logic that walks those structures is broken.** Verified empirically: rest a
sell @100, send a crossing buy @100 → **nothing matches**, both orders rest on
opposite sides (an impossible "crossed book"). The bugs are shallow, not structural,
which is why this is worth iterating on rather than rewriting.

### Bug inventory (ordered by severity)

1. **Matching never executes.** `restSellOrder` stores the **head sentinel** (whose
   `data == null`) as the map value, so `match*Order`'s guard
   `sellOrderHead.data == null` bails before touching a real order. You must start
   iterating at `head.next`, not `head`.
2. **Only matches at the exact same price** — violates **price priority (FR-10)**.
   A buy @101 must sweep resting sells at 100, 100.5, 101 (cheapest first). The
   `TreeMap` ordering is currently never used. Walk from the best opposing price inward.
3. **Market orders rest in the book** — market buy gets price `Long.MAX_VALUE`,
   `get()` returns null, so it rests. Violates **FR-6** ("MUST NOT rest").
4. **Orders added to an existing price level are never put in `orderMap`** — only the
   first order at each price is tracked, so the rest can't be cancelled or queried.
5. **`cancelOrder` / `removeOrder` NPE** instead of failing cleanly on an unknown uid
   — violates **FR-16** ("fail explicitly without corrupting state").
6. **Empty price levels leak** — `removeOrder` never removes the level key from the
   `TreeMap` when its last order is gone.
7. **No trades are produced** — matching mutates `filledQty` silently and returns a
   boolean. No execution price, no trade record, no event stream (**FR-13/14/23**).
   The trade feed is the engine's actual *output*.
8. **IOC / FOK / POST are half-wired** — factories exist, but the controller rejects
   them and the matcher has no remainder / all-or-nothing / no-cross logic.

### Design issues (not bugs, but they bite at scale)

- **Everything is `static`** — global mutable state. Can't be instantiated per symbol,
  isn't thread-safe, and is hostile to testing (one shared book across all tests).
- **`OrderNode` conflates the list container with its elements** (every node carries
  `head`/`tail`/`next`/`prev`). This is the root of bugs #4–#6. Split it into a
  `PriceLevel` (owns `head`, `tail`, `totalQty`) and a plain order node
  (`next`, `prev`, `order`).
- **Package/path mismatch** — files declare `package svc.*` but live under
  `src/main/com/imc/svc/*`. Breaks conventional tooling and jdtls.
- **No build file, no tests, no entry point.**

---

## The plan, end to end

```
Phase 0  Mental model — what you're actually building
Phase 1  Project skeleton — layout + Maven (unblocks everything)
Phase 2  jdtls / NvChad — make the editor understand the project
Phase 3  Tests — lock behavior before you change it
Phase 4  Fix matching — the core algorithm (you write it)
Phase 5  JMH — benchmark correctly
Phase 6  Profiling — async-profiler + JFR
Phase 7  Driving it from NvChad
```

Ordering is deliberate: **infrastructure → correctness → measurement.** You can't
profile a matcher that doesn't match, and you can't trust a fix you can't test.

---

## Phase 0 — Mental model

A single-symbol matching engine at runtime is three structures (above). The structures
are already right; the bugs are all in the logic that walks them.

One concept that shapes the whole roadmap: **a production matching engine is
single-threaded per book** — not because threading is hard, but because a
deterministic, lock-free, cache-resident single writer is *faster* than coordinating
threads on shared state (the LMAX Disruptor insight). The current `static` fields are
*accidentally* single-instance; the goal is to make that a *deliberate* single-writer
design, not a global-state accident.

---

## Phase 1 — Project skeleton

**Why:** No build file + package/path mismatch blocks every tool (compiler, jdtls,
test runner, JMH). Java's hard rule: **the folder path under the source root must equal
the package name with dots → slashes.** Your files say `package svc.model;` but live at
`src/main/com/imc/svc/model/` — the `com/imc` prefix isn't in the package, so no build
tool can guess the layout.

**Decision:** commit to a package name. The `com/imc` in your path suggests
`com.imc.svc.*` (reverse-domain convention) — take that. Maven expects
`src/main/java` for code and `src/test/java` for tests.

**How:**

1. Restructure to Maven's standard layout:
   ```
   OrderBook/
     pom.xml
     src/main/java/com/imc/svc/...   (existing files, moved)
     src/test/java/com/imc/svc/...   (tests, Phase 3)
   ```
   Move source: `src/main/com/imc/svc/...` → `src/main/java/com/imc/svc/...`.
   `src/main/java` becomes the source root, so `.../com/imc/svc/model/Order.java`
   must declare `package com.imc.svc.model;`.

2. Update every `package` / `import` line: `svc.model` → `com.imc.svc.model`, etc.
   (jdtls offers a "correct package declaration" quick-fix once it's running, but do
   the first pass by hand to cement *why* the rule exists.)

3. Create `pom.xml` at the repo root — read the comments, don't just paste:
   ```xml
   <project xmlns="http://maven.apache.org/POM/4.0.0" ...>
     <modelVersion>4.0.0</modelVersion>

     <!-- groupId.artifactId is this project's global name -->
     <groupId>com.imc</groupId>
     <artifactId>orderbook</artifactId>
     <version>0.1.0-SNAPSHOT</version>   <!-- SNAPSHOT = pre-release / in-dev -->

     <properties>
       <!-- compile to Java 21 bytecode + language features -->
       <maven.compiler.release>21</maven.compiler.release>
       <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
     </properties>

     <dependencies>
       <!-- JUnit 5 — test classpath only (scope=test), never shipped -->
       <dependency>
         <groupId>org.junit.jupiter</groupId>
         <artifactId>junit-jupiter</artifactId>
         <version>5.11.3</version>
         <scope>test</scope>
       </dependency>
     </dependencies>
   </project>
   ```
   The `groupId/artifactId/version` triple is your project's unique identity.
   `maven.compiler.release` is what makes `mvn compile` target 21. `<scope>test</scope>`
   keeps JUnit off the runtime classpath — which *enforces* your **NFR-5** ("no external
   deps for the core engine").

4. Verify: `mvn compile`. Green here = jdtls will be green too. This is your new ground truth.

> **Gradle alternative:** `build.gradle` with the `java` plugin and JUnit under
> `testImplementation`. Same `src/main/java` layout. Everything below is identical;
> only the command changes (`./gradlew` vs `mvn`).

---

## Phase 2 — jdtls / NvChad

**Why:** jdtls is a *language server* — a separate process that answers the editor's
questions (types, definitions, diagnostics). It infers your **classpath** from a build
file. With no `pom.xml` it treated the folder as a loose pile of files; after Phase 1 it
has a project to read, so diagnostics / go-to-def / autocomplete (incl. for JUnit, JMH)
all work.

**How:**

1. **Root detection** — jdtls scans upward for marker files; nvim-jdtls defaults include
   `pom.xml`, `build.gradle`, `mvnw`, `.git`. You have `.git` and now `pom.xml`, so
   opening any `.java` file attaches jdtls to the repo root. Nothing to configure.
2. **Confirm** — open a `.java` file, run `:LspInfo`. Expect `jdtls` active with root =
   `OrderBook/`. First attach builds a workspace index (progress spinner) — let it finish.
3. **Use it** — `gd` (definition), `gr` (references), `K` (hover type), live diagnostics
   matching `mvn compile`. **`google-java-format`** is already installed; wire NvChad's
   `conform.nvim` `java` formatter to it so your existing 2-space style is preserved on save.
4. **If jdtls misbehaves after the restructure** — usually a stale workspace cache (stored
   outside the repo under `~/.local/share` or `~/.cache` per your nvim-jdtls `workspace`
   path). Delete that project's workspace folder and reopen for a clean re-index.

---

## Phase 3 — Tests

**Why:** You're about to rewrite the matching core. Write a test that fails *because of
the bug* first (buy@100 crossing resting sell@100 → a trade + empty book; currently red).
When the fix turns it green you have objective proof, not vibes. README **AC-2/AC-3**
require this suite.

**How:**

1. Tests live at `src/test/java/com/imc/svc/...`, mirroring the class-under-test's package.
   JUnit 5 discovers any `@Test` method.
2. First test shape (you write the body):
   ```java
   @Test
   void crossingBuyMatchesRestingSell() {
     // given a resting sell @100 qty 10
     // when a buy @100 qty 10 arrives
     // then a trade for qty 10 @100 is produced, and the book is empty
   }
   ```
   You'll hit a wall: **there's no query API to assert against** (no top-of-book, no
   order-status, no trade output). That wall is the point — the engine's observable
   surface (README §6) must exist before it's testable. Designing for testability and
   designing a clean public API are the same task.
3. Run with `mvn test` (Maven compiles `main`, then `test`, then runs `@Test`s via Surefire).
4. For **NFR-3 / VR-6** (correctness under volume): a **randomized stress test** — generate
   thousands of random valid orders, apply them, and after *every* op assert the invariant
   **"aggregate depth at each level == sum of resting order quantities."** This catches
   state-corruption bugs hand-written cases miss.

---

## Phase 4 — Fix the matching core

**Why:** This is the actual engine — write it yourself; that's the value. Use the Phase 3
tests as a guide rail: red → green, one behavior at a time.

**Algorithm (incoming buy; sell is the mirror):**

1. **Walk the opposing book from the best price inward.** For a buy, best ask = lowest
   sell price = `sellSideBook.firstEntry()`. Loop while an opposing level exists **and the
   prices cross** (`incomingBuyPrice >= bestAskPrice`). Fixes bugs #1, #2, #3 at once — no
   more exact-price `get()`, and a market buy (price `MAX_VALUE`) naturally crosses every
   level until liquidity runs out.
2. **Within a level, match FIFO** from `head.next` (the real first order, **not** the
   sentinel — bug #1). Consume `min(incoming.remaining, resting.remaining)` each step.
   **Emit a trade** at the **resting** order's price (price improvement → aggressor,
   **FR-14**). The trade record (both uids, qty, price) is the engine's real output
   (**FR-13/23**).
3. **Maintain invariants as you go:**
   - Resting order fully filled → unlink node *and* `orderMap.remove(uid)`.
   - Level's last order gone → **remove the level key from the `TreeMap`** (bug #6).
   - Incoming order exhausted → stop.
4. **After the walk, apply the per-type remainder policy:**
   - **LIMIT** remainder → rest it, with `orderMap.put` on **every** insert (bug #4).
   - **MARKET** remainder → cancel; never rest (**FR-6**).
   - **IOC** → cancel remainder. **FOK** → check fillability *before* matching, all-or-nothing.
     **POST** → reject if it would cross.
5. **Make cancel safe** (bug #5): `orderMap.get(uid)` may be null → return a typed
   "not found" outcome, never NPE (**FR-16**).

**Structural cleanup that prevents recurrence:** split `OrderNode` into a `PriceLevel`
(owns `head`, `tail`, running `totalQty`) and a plain order node (`next`, `prev`, `order`).
Bugs #4–#6 all came from one type doing two jobs. Bonus: `PriceLevel.totalQty` gives
**FR-20 depth** for free and makes the **VR-6** invariant a one-line assertion.

Do it incrementally: exact-price matching working + tested → best-price walk → trades →
each order type. Let the tests catch each step.

---

## Phase 5 — JMH (benchmarking)

**Why JMH, not a `nanoTime()` loop:** the JVM is a moving target. (a) The JIT optimizes
*while running* — the first ~10k iterations are slow then suddenly fast, so you must
**warm up**. (b) The JIT *deletes* code whose result you don't use (dead-code elimination),
so a naive benchmark can measure nothing while reporting a number. (c) GC + scheduling add
noise. JMH (from the JDK team) forks fresh JVMs, runs warmup, and gives you `Blackhole` to
consume results so the JIT can't elide them.

**How it works mechanically:**
- Annotate a method with `@Benchmark`. JMH's **annotation processor** generates the harness
  (warmup loops, timing, Blackhole plumbing) into `target/generated-sources` at compile time.
- Canonical run model: an **uber-jar**. `mvn package` shades your benchmarks + JMH runtime
  into `target/benchmarks.jar`; run it with `java -jar target/benchmarks.jar`. Running in a
  clean JVM (not inside Maven) is *why* the numbers are trustworthy.

**Setup:** add `jmh-core` (dependency) + `jmh-generator-annprocess` (annotation processor)
to `pom.xml`, plus `maven-shade-plugin` producing `benchmarks.jar` with main class
`org.openjdk.jmh.Main`. (This is exactly what the official `jmh-java-benchmark-archetype`
generates — read its `pom.xml` as a reference.)

**Annotations that matter:**
- `@BenchmarkMode(Mode.Throughput)` — ops/sec (your **SG-4** number).
  `Mode.SampleTime` — a **latency distribution** (percentiles), which for an exchange
  matters far more than the average.
- `@Warmup(iterations=5)` / `@Measurement(iterations=10)` / `@Fork(2)` — discard JIT warmup,
  then measure, across fresh JVMs.
- `@State(Scope.Benchmark)` — holds a pre-built book so you measure *matching*, not setup.

**Reading results:** report **p50 / p99 / p99.9 / max latency**, never the mean — the tail
is what kills you. Learn **coordinated omission**: if the benchmark pauses (GC) then "catches
up," it silently under-counts the worst latencies, making a bad engine look good. JMH's
`SampleTime` mode + the **HdrHistogram** library expose this honestly. This is the #1 way
latency benchmarks lie.

---

## Phase 6 — Profiling

**Why:** Benchmarks say *how fast*; profilers say *why*. On the road to millions of ops/sec
the enemy is usually **allocation and GC pauses**, not CPU.

**Two tools:**

1. **JFR (Java Flight Recorder)** — in JDK 21 already, zero install. Run with
   `-XX:StartFlightRecording=filename=run.jfr`, open `run.jfr` in **JDK Mission Control**
   (free download): CPU hot methods, allocation-per-type, GC timeline. Start here.
2. **async-profiler** — industry-standard JVM flamegraphs, lower overhead than JFR for CPU.
   Not installed yet: `brew install async-profiler` → `asprof`. Attach to a running JVM:
   - `asprof -e cpu -d 30 -f cpu.html <pid>` — 30s CPU flamegraph.
   - `asprof -e alloc -d 30 -f alloc.html <pid>` — **allocation** flamegraph (the important
     one: shows which objects the hot path creates — the input to "zero-allocation hot path"
     work, the real justification for future FFM / off-heap / `Unsafe`).
   - Output is HTML — **view in a browser, not nvim.**
3. **GC visibility:** `-Xlog:gc*:file=gc.log`. For an exchange a single 50ms pause is a
   catastrophe — this log is how you'd notice.

The throughline: **JMH says "it got slower," async-profiler's alloc view says "because this
method now allocates a `Long` per match," → you know what to fix.** That loop is what
justifies each future rewrite — you'll have *numbers* proving the step helped.

---

## Phase 7 — Driving it from NvChad

**Why:** Don't leave nvim to build/test/bench. A thin task layer triggered from a terminal
split keeps the moving parts visible.

**How:** a `Makefile` at the repo root:
```
make build     →  mvn compile
make test      →  mvn test
make bench     →  mvn -q package && java -jar target/benchmarks.jar
make profile   →  java -XX:StartFlightRecording=filename=run.jfr -jar target/benchmarks.jar
```
Run from nvim via `:terminal` (`:term make test`). For a task-runner UI with quickfix
integration, **overseer.nvim** is the NvChad-friendly option; a `Makefile` + `:terminal`
is enough to start and keeps things visible — the spirit of this exercise.

---

## Suggested order for the first week

1. Phase 1 (skeleton + `pom.xml`) → `mvn compile` green.
2. Phase 2 → `:LspInfo` shows jdtls happy.
3. Phase 3 → one failing test proving the crossed-book bug.
4. Phase 4 → drive that test green; then add the best-price walk and trades.
5. Phases 5–6 → only once the engine actually matches.

Don't skip ahead to JMH — a fast wrong answer is worthless, and you can't profile what
doesn't run.

---

## Mapping to the README spec

| Area              | Requirement(s)              | Status today        |
|-------------------|-----------------------------|---------------------|
| Price priority    | FR-10                       | Broken (exact-price only) |
| Time priority     | FR-11                       | Structure OK, unused |
| Market no-rest    | FR-6                        | Broken (rests)      |
| Trades / events   | FR-13, FR-14, FR-23, FR-24  | Missing             |
| Cancel safety     | FR-15, FR-16                | NPEs                |
| Amend             | FR-17, FR-18                | Not implemented     |
| Queries (TOB/depth/status) | FR-19, FR-20, FR-21, FR-22 | Missing      |
| Validation at boundary | API-8, VR-1, VR-2      | Partial (`Validator`) |
| IOC / FOK / POST  | FR-7, FR-8, FR-9 (SG-1)     | Factories only      |
| Determinism       | NFR-1                       | Single-threaded by accident |
| Volume invariants | NFR-3, VR-6                 | No stress test yet  |
| Benchmark harness | SG-4                        | Not started (Phase 5) |
