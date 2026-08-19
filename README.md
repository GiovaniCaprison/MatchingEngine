# MatchingEngine

A single-symbol limit order book and matching engine in Java, built as the foundation for a
full exchange. The long-term target is 20M operations per second on the matching hot path.

Status: the engine matches. Submit, cancel and amend work through a validating boundary, the
five order types are dispatched, one sequencer mints all identity, and an order registry answers
status queries after an order has left the book. There are no tests at the moment: the suite that
existed was written before the engine and asserted very little, so it was removed rather than
repaired. See `docs/TESTING.md` for what replaces it.

## Design in one minute

- Single writer per book. One thread mutates the book, ids are minted at one ordered point, and
  replaying the same input is reproducible bit for bit. Determinism comes free from the
  architecture, and concurrency comes from partitioning books across threads.
- Prices are scaled longs. `100.25` is stored as `1002500`, and conversion happens at the I/O
  edge. Floating point cannot represent decimal prices exactly.
- Typed outcomes. `submit`, `cancel` and `amend` return sealed result types, so "rejected" and
  "not found" are values that the compiler makes you handle.
- The core emits, it does not return. Results go into caller-supplied sinks as primitives, and
  materialising objects is the edge's job.
- Correctness before speed. JMH and profiling come once the engine is proved, because a fast
  wrong answer is worthless.

## Layout

```
src/main/java/com/imc/me/    the engine: domain, book, matching, sequencer, registry, event
src/test/java/com/imc/me/    boundary tests, book tests, the scenario corpus
src/test/resources/scenarios/  scenario fixtures, .input and .expected pairs
```

## Build and test

Requires JDK 21 and Maven.

```
mvn compile          build the engine
mvn test             the fast and scenario lanes
mvn test -Pstress    adds the randomised lane, for CI and pre-merge
```

## Documentation

- [REQUIREMENTS.md](docs/REQUIREMENTS.md), the specification as a flat list. Ids from this file
  are referenced from javadoc throughout the source.
- [OOD_PRINCIPLES.md](docs/OOD_PRINCIPLES.md), why the code is shaped the way it is: mutation
  ownership, the core/edge border, order-type variation, the allocation budget. Read this before
  changing a signature.
- [ENGINEERING_GUIDE.md](docs/ENGINEERING_GUIDE.md), the model, the matching algorithm, and the
  benchmarking and profiling road.
- [TESTING.md](docs/TESTING.md), what gets proved and how.

## How to contribute

So while this is likely not something anyone else is ever going to contribute to as it is
intentionally a personal project which I am building for my own personal enjoyment,
I am still going to document this process for anyone (including myself 100 months from now)
such that reading through commits doesn't look like a scene from Apocalypse Now.

I like to do something like `(broad term/catagory of whatever this is): what I am actually doing`
for commit messages. I have not been doing from the first couple of commits as I was still trying
to ensure that I did not accidentally explode by hippocampus from all of the new financial concepts
that I was learning. However, that is how you should expect to see the things being committed.

The beauty of this project is that TDD is natural. A ME is deterministic which means invariants
can be black box tested and so long as they are held, we dgaf what implementation is sitting
beneath the abstraction. So that is the approach we are taking, tests are defined as invariants
which we use as guides for what we are actually building.

AI USAGE is something which I do not want in this project apart from documentation (md files),
quality and design guidance, or as a learning guide for financial topics like "WTAF is a LIMIT IOC.
The code itself, should absolutely be something I do myself. The entire point of this is for me
to learn and so AI does not work well as far as implementation in concerned.

That is about it for now - or forever - I have no clue ))
