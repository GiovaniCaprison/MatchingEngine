# matching-engine

A single-symbol limit order-book matching engine in Java, built test-first as the
foundation for a full exchange. Long-term target: 20M ops/sec on the matching hot path.

Status: **scaffolding complete, engine not yet implemented.** The build, package layout,
layered test suite, and coverage matrix are in place; `mvn test` is green vacuously. The
domain types, public API, and matching logic are the next work.

---

## Design in one minute

- **Single-writer per book.** One thread mutates the book, ids are minted at one ordered
  point (the sequencer), and replaying the same input is bit-for-bit reproducible. This is
  what makes determinism free and is the path toward the LMAX-style hot path.
- **Prices are scaled `long`s, never floats.** `100.25` is stored as `1002500`; conversion
  happens at the I/O edge.
- **Typed outcomes, not booleans or exceptions.** `submit`/`cancel`/`amend` return sealed
  result types, so "rejected" and "not found" are values, not nulls.
- **Correctness before speed.** `infrastructure → correctness → measurement`. JMH and
  profiling come only once the engine actually matches.

---

## Layout

```
src/main/java/com/imc/me/    engine code (domain, book, matching, sequencer, ...)
src/test/java/com/imc/me/    tests organised by LAYER (explicit, golden, property,
                             structural, coverage) — see TESTING.md
src/test/resources/          golden fixtures + requirements.txt (spec source of truth)
benchmarks/                  JMH (deferred until the engine is correct)
docs/nfr-fr.md               the NFR/FR spec
```

---

## Build & test

Requires JDK 21+ and Maven.

```
mvn compile            # build the engine
mvn test               # fast + golden lanes (default inner loop)
mvn test -Pstress      # add the jqwik property lane (slower; CI / pre-merge)
```

The coverage matrix (`coverage/CoverageMatrixTest`) fails the build if any in-scope
requirement has no test, and writes a report to `target/coverage-matrix.md`.

---

## Documentation

- **[ENGINEERING_GUIDE.md](ENGINEERING_GUIDE.md)** — the model, the matching algorithm, the
  project layout, and the measurement road (JMH benchmarking + profiling).
- **[TESTING.md](TESTING.md)** — the TDD roadmap and the test-suite reference: the five test
  layers, the requirement→layer map, cost lanes, the coverage matrix, and the step-by-step
  build order.

New here? Read this file, then `TESTING.md` for what to do next.
