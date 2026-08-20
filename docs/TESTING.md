# Testing

The engine is a deterministic function from an ordered input log to an ordered output log.
Almost everything worth proving about it can therefore be proved by diffing text, which is the
one idea this document is built on.

## Current state

26 tests, nothing skipped. Thirteen of them are scenarios: the harness is wired and the corpus is
the layer carrying most of the weight, as intended. Ten are boundary examples through the public
API, and three sit in the book package to check a contract that is not observable from outside.

Still missing: the reference model, and everything in the benchmark and soak lanes.

## What we test, and how

The scenario corpus is the primary gate. A fixture is a command sequence and the blessed
output: the trade stream, each command's result, and the resulting book. Fixtures live in
`src/test/resources/scenarios` as `.input` and `.expected` pairs, carry their requirement id in
the filename, and are discovered by a test factory, so adding a scenario means adding two text
files. The grammar is specified in `SCENARIO_FORMAT.md`. This layer owns the order types, the matching rules, amend priority, sweeps, the event
stream and determinism. It is also the only layer that survives a rewrite: the grammar is the
contract, so a second implementation in another language runs the same corpus behind a thin
runner.

A reference model catches what the corpus does not. The plan is a second matching engine that
is obviously correct and far too slow to ship: a flat list of resting orders, linear scan for the
best price, no intrusive lists. Randomised command sequences run through both and the complete
output is diffed. A priority bug is defined by disagreement with the right answer, and no
invariant captures it, so this is the only mechanism that finds one. Invariant properties catch
aggregate drift and leaks; the model catches "it matched the wrong order".

Unit tests are a thin surface. At the boundary they are the atomic "yes, this does this"
statements: an invalid quantity is refused, a re-cancel reports not-found, an empty side reads as
empty. They live in `com.imc.me.boundary` so the compiler stops them reaching past the public
API. A small number live in `com.imc.me.book` instead, because the intrusive list links and entity
mutators they assert on are package-private, and each one of those carries a written reason why
the boundary was insufficient.

Structural rules are absolute bans only. No floating point in the core, no clock or randomness,
no concurrency machinery, no streams on the hot path, no mutable collection returned from a public
method, no validation below the boundary. A rule earns its place only if it constrains code that
does not exist yet, a violation would survive review, and it says something the compiler does not.

Performance is measured, not asserted. Latency percentiles from JMH in a forked JVM, allocation
from `gc.alloc.rate.norm`, and the asymptotic claims from a deterministic probe that counts node
visits and shows the count does not grow with book size. Wall-clock assertions inside the unit
suite are the flakiest thing available and contradict the project's own measurement discipline.

## What we do not test

If the compiler already guarantees it, there is no test. Sealedness, exhaustive switches over
an enum with no default arm, and mutators that cannot be named outside their package are enforced
at build time, and a test that restates a declaration is a worse copy of the source text. The
suite previously asserted `SubmitResult.class.isSealed()`, which is the trap this rule exists to
avoid.

Judgement calls are reviewed, not tested. OOD-17 and OOD-18 are about not foreclosing a future,
and encoding today's class list in an assertion is the mistake they warn against.

## What we do not build yet

There is nothing to integrate. No I/O, no transport, no persistence, no process boundary. What
would be an integration test here is the boundary test. Creating empty integration and end-to-end
packages now would recreate the problem that removing the old suite solved, so the trigger
conditions are written down instead:

- an integration lane when a second component exists to wire to, such as a wire decoder, a
  journal-fed sequencer, or a recovery path
- an end-to-end lane when there is a transport and a client, at which point the test is a client
  sending encoded messages over the real path, plus fuzzing at the decode boundary
- a soak lane once a requirement names a book size

## Choosing a mechanism

Four questions, in order. The first that applies decides it.

1. Is it observable at the public boundary? If not, go to 4.
2. Can the expectation be written as a literal? Small, one or two calls: a unit example. Rich,
   a trade stream plus book state: a scenario fixture.
3. No literal oracle? If a slow reference implementation can produce the answer, differential.
   If the only expectation is an invariant over any stream, property. If it is a number, benchmark.
4. Otherwise: guaranteed by the compiler, no test. A dependency or type-shape rule over a
   package, structural. An internal defect genuinely unreachable from the boundary, a white-box
   unit test with a written justification. A judgement call, review.

## Lanes

Placement says what a test can see. Tags say what it costs.

| Tag | Contents | Run |
|---|---|---|
| `fast` | boundary and white-box unit, structural rules, complexity probes | `mvn test` |
| `golden` | the scenario corpus | `mvn test` |
| `stress` | property and differential | `mvn test -Pstress` |

Benchmarks never run under `mvn test`.
