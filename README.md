# MatchingEngine

A limit order book matching engine, and a study of how much its implementation matters.

The engine is scoped to what a real matching engine owns, so it can later be a component of a larger
exchange. Where that boundary falls, and what sits either side of it, is in
[SCOPE.md](docs/SCOPE.md).

The study crosses the implementations below against how much of the engine's feature set the input
actually uses, and asks whether a structural advantage survives a production one. The questions, the
design and what is not claimed are in [METHODOLOGY.md](docs/METHODOLOGY.md).

## Implementations

Four rungs, each implemented in both languages at a matched layout, each satisfying one interface and
measured on the same logs.

| Rung | Book | Command handling |
|---|---|---|
| naive | one list, scanned | an order object per command |
| indexed | sorted price levels, index by order id | an order object per command |
| pooled | as indexed | pooled orders, no boxing, no steady state allocation |
| flyweight | flat price ladder, orders in a slab | fields read in place from the buffer |

Implementing every rung twice is what lets language separate from layout: the step between rungs
isolates layout, and the step between languages at one rung isolates the runtime.

The naive rung has a second variant carrying limit and market orders only. Comparing it against the
full one gives the cost of a feature existing, which cannot be measured with a runtime flag, since a
disabled feature behind a branch still occupies the method and the object layout (P-16).

## Layout

```
schema/     the SBE schema, generated into both languages
corpus/     scenario fixtures and their blessed output, language neutral
docs/
java/       parent pom, then api, protocol, one module per rung, conformance, benchmarks
cpp/        one target per rung, conformance, benchmarks
results/    one directory per run: manifest, histograms, counters
```

Language first at the top level, so each build system owns its own subtree and the two sides stay
symmetric as rungs are added.

The schema and the corpus sit above both, which makes the shared contract structural. Neither language
owns the file that defines the messages, nor the fixtures that hold both to the same behaviour.

Within a language, each implementation is its own module. Benchmarks need one implementation per
process, because two loaded into one JVM make the call site megamorphic and pollute every number after
that. And a conformance suite that depends only on the api cannot reach inside an implementation, so
black box testing holds without relying on discipline.

## Documentation

- [SCOPE.md](docs/SCOPE.md), where the engine ends and the exchange begins
- [PROTOCOL.md](docs/PROTOCOL.md), the commands and events that cross the boundary
- [REQUIREMENTS.md](docs/REQUIREMENTS.md), what the engine must do, and how each line is shown to hold
- [PRINCIPLES.md](docs/PRINCIPLES.md), why the code is shaped as it is. Read before changing a
  signature.
- [TESTING.md](docs/TESTING.md), the correctness mechanisms and the corpus format
- [METHODOLOGY.md](docs/METHODOLOGY.md), how performance is measured, with what, and what is recorded

## Build

Requires JDK 21 and Maven for the Java side, CMake and a recent GCC or Clang for the C++ side.

```
cd java
mvn compile     build everything, generating the codecs first
mvn test        unit tests, the corpus, and the requirement coverage gate
```

```
cd cpp
cmake -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build
ctest --test-dir build
```

Agrona reaches `jdk.internal.misc.Unsafe` for buffer access, so the build passes
`--add-exports java.base/jdk.internal.misc=ALL-UNNAMED`, declared once in the parent pom. Anything
embedding the engine needs the same flag.

Benchmarks run outside the test phase, one implementation per process, and write a directory under
`results/`. On the Java side that is a shaded jar; on the C++ side a binary.

```
mvn package && java -jar java/matching-benchmarks/target/benchmarks.jar
cpp/build/benchmarks
```

Neither runs under the test command. A wall clock assertion in a unit suite is flaky and measures
nothing that can be compared. Measurement runs belong on a controlled machine, and the requirements
for one are in [METHODOLOGY.md](docs/METHODOLOGY.md).

## Conventions

Commit messages read `(category): what I am actually doing`. Branches are one change each and land
through a pull request.
