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
corpus/     rules and scenarios with their blessed output, language neutral
docs/       scope, protocol, requirements, principles, testing, methodology
scripts/    the analysis, the campaign matrix, and the metal box setup
java/       matching-protocol      the generated codecs
            matching-api           the three-method boundary
            matching-flow          the generator and the command log
            matching-conformance   the corpus runner and the consumer's book
            matching-gates         the two build gates
            matching-naive         rung zero, the whole remit
            matching-lean          the limit-and-market arm of the feature cost question (P-16)
            matching-calibration   a real session measured, and replayed as commands
            matching-benchmarks    the measurement harness and the runner
cpp/        protocol, api, conformance, naive, lean, benchmarks: the same shapes at matched layouts
results/    one directory per run: manifest, histograms, counters
```

That block is the map. Each module says the rest of what it is at the top of its own sources, so
there is no per-directory documentation to fall out of date.

Language first at the top level, so each build system owns its own subtree and the two sides stay
symmetric as rungs are added.

The schema and the corpus sit above both, which makes the shared contract structural. Neither language
owns the file that defines the messages, nor the fixtures that hold both to the same behaviour.

Within a language, each implementation is its own module. Benchmarks need one implementation per
process, because two loaded into one JVM make the call site megamorphic and pollute every number after
that. And a conformance suite that depends on the api and the protocol, and on no implementation,
cannot reach inside one, so black box testing holds without relying on discipline.

## Documentation

- [SCOPE.md](docs/SCOPE.md), where the engine ends and the exchange begins
- [PROTOCOL.md](docs/PROTOCOL.md), the commands and events that cross the boundary
- [REQUIREMENTS.md](docs/REQUIREMENTS.md), what the engine must do, and how each line is shown to hold
- [PRINCIPLES.md](docs/PRINCIPLES.md), why the code is shaped as it is. Read before changing a
  signature.
- [TESTING.md](docs/TESTING.md), the correctness mechanisms and the corpus format
- [METHODOLOGY.md](docs/METHODOLOGY.md), how performance is measured, with what, and what is recorded

## Build

Requires JDK 25 and Maven for the Java side, CMake and a recent GCC or Clang for the C++ side. The JDK
is pinned in `.tool-versions`, since a runtime's build is part of what a measurement measured and every
run records the one it ran on.

```
cd java
mvn compile     build everything, generating the codecs first
mvn test        unit tests, the corpus, and the gates
```

```
cd cpp
cmake -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build
ctest --test-dir build
```

Configuring fetches the codec generator and the test framework by exact version and checksum into the
build directory, so it needs the network once and never again. Formatting is `google-java-format` on
one side and `clang-format` on the other, both at a hundred columns and both pinned, one in the
parent pom and one in `.clang-format-version`, so the two trees wrap alike and no two machines
disagree about how. The
Java formatter is pinned in the parent pom and `mvn verify` fails on a file it would change, so the
format is part of the build rather than a habit; `mvn spotless:apply` fixes what it refuses. A
pre-commit hook formats what is being committed in either language, and the first Maven build
installs it by pointing `core.hooksPath` at `hooks/`. A machine that only ever builds the C++ side
runs `git config core.hooksPath hooks` once by hand.

Agrona reaches `jdk.internal.misc.Unsafe` for buffer access, so the build passes
`--add-exports java.base/jdk.internal.misc=ALL-UNNAMED`, and the harness places its threads and reads
counters through the foreign function API, which needs `--enable-native-access=ALL-UNNAMED`. Both are
declared once in the parent pom, and anything embedding the engine needs the first of them. That is
the internal Unsafe the runtime uses itself, which is why the flag is needed and why the access stays
intrinsified. The memory access methods on `sun.misc.Unsafe` are on the removal path, and nothing
here goes near them.

Benchmarks run outside the test phase, one implementation per process, and write a directory under
`results/`. On the Java side that is a shaded jar; on the C++ side a binary.

```
mvn package && java -jar java/matching-benchmarks/target/benchmarks.jar
cpp/build/benchmarks/benchmarks --implementation naive --log session.log
```

The C++ runner always replays a log file, because one generator exists and neither language owns
it. A campaign is one process per cell via `scripts/matrix.py`, and every table comes out of the
run directories via `scripts/analyze.py`.

Neither runs under the test command. A wall clock assertion in a unit suite is flaky and measures
nothing that can be compared. Measurement runs belong on a controlled machine, and the requirements
for one are in [METHODOLOGY.md](docs/METHODOLOGY.md).

## Conventions

Commit messages read `(category): what I am actually doing`. Branches are one change each and land
through a pull request, and every pull request runs the whole build on a runner: `mvn verify` on the
Java side, then the CMake build, `ctest` and `clang-format` on the C++ side. What lands is what
passed on a machine nobody's editor had prepared.
