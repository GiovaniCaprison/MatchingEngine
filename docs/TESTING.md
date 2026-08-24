# Testing

How correctness is established. Measurement is in `METHODOLOGY.md`; the two share a generator and
nothing else.

Every requirement in `REQUIREMENTS.md` names one mechanism. This document says what each mechanism is
for and which failures it is capable of catching.

## Unit

One rule, one test, at the public interface. Small enough that the expected result is written as a
literal, and named so the requirement it covers is visible in the test output.

This is the bulk of the suite because most of the remit is single rules: a market order does not rest,
a price off tick is refused, displayed quantity is consumed before hidden.

A unit test proves the rule for the case it states. It does not prove the rule holds for sequences
nobody wrote down, which is what the corpus and the property tests are for.

## Corpus

A fixture is a command sequence and the blessed output it must produce. Fixtures are plain text, live
in `corpus/`, and are replayed by every implementation in every language.

The corpus catches what unit tests structurally cannot: interaction. A stop cascade that fires during
an iceberg replenishment inside an auction uncross is one fixture and would be six unit tests that
each pass while the combination is wrong.

When output changes legitimately, the runner prints the run back as a fixture so it can be read and
pasted over the file. Read the diff first. A blessed snapshot is worth what the last person to look
at it was paying attention to.

## Property

Invariants that must hold after any sequence, checked over generated input. Aggregate quantity at a
price equals the sum of its orders. No empty level and no unreferenced order survives. The trigger
book holds exactly the stops that have not fired.

These catch drift, which is the failure mode where every individual operation is correct and the
structure is slowly wrong. No fixture finds that, because a fixture only checks the states somebody
imagined.

## Differential

Two implementations fed identical generated input, output diffed. A disagreement means one is wrong,
and the naive engine is the one written to be obviously right, so it is the arbiter.

This is the only mechanism that catches an allocation error nobody thought to write a fixture for.
An invariant cannot find it, because a book that allocated to the wrong order at the same price is
internally consistent.

## Compiler

Where a violation fails the build there is no test. An exhaustive switch over an enum with no default
arm, and mutators that cannot be named outside their package, are enforced at compile time. A test
that restates a declaration is a worse copy of the source text.

## Review

Judgement that cannot be automated without encoding today's code as the specification. Single writer
discipline, dependency freedom of the matching core, and whether an abstraction has a second
implementation worth naming.

## The gates

The build fails when a requirement marked `unit` is not named by a test.

The check reads `REQUIREMENTS.md` as the source of truth, extracts the ids marked `unit`, and scans
test sources for each id in a display name. It fails three ways: a `unit` requirement no test names, a
test naming an id absent from the document, and a test naming an id whose mechanism is `compiler` or
`review`.

It also fails when a test that names a requirement contains no assertion. That is not hypothetical
paranoia: an earlier version of this project had seventeen empty test bodies carrying requirement
annotations, and its coverage gate reported them as covered. A gate that rewards the claim rather than
the check produces claims.

The gate is gameable, as any gate is. An empty test that fails a build is easier to notice and reject
than a missing test that produces a slightly shorter report.

A second gate fails the build on documents that have drifted apart. Every requirement id cited
anywhere must exist in `REQUIREMENTS.md` and every principle id in `PRINCIPLES.md`. Every message the
schema defines must be described in `PROTOCOL.md` and reachable from a corpus directive, and every
identifier `PROTOCOL.md` quotes must be defined in the schema. Documents disagreeing with each other
is the failure this project has actually had, more than once, and it is not a thing review reliably
catches: a stale claim reads perfectly well on its own page.

## Where tests live

Placement is decided by what a test needs to see, and the compiler enforces it.

A test that drives an engine through nothing but the public interface lives outside the
implementation's package, so it cannot reach an internal even by accident. Those are the tests a
rewrite has to keep passing.

A test that must observe an internal structure lives in that implementation's package, and each one
carries a written reason why the public interface was insufficient. There should be few of them.

The corpus depends on the api and the protocol and on no implementation, so it cannot be flattered by
one's internals. The gates depend on nothing at all, and read the documents and the sources as text.

## Corpus format

One directive per line. A line whose first non-blank character is `#` is a comment. Blank lines are
ignored. Fields are separated by any run of spaces, so columns can be aligned. There are no trailing
comments, because `#` is also the order reference sigil.

An order reference is `#n`, counting `NEW` directives from one, and it is the client order id that
order was entered with. A command names an order that way, so a fixture needs nothing an engine has to
report first.

An event names an order by the engine's id, and that is never written down: asserting it would test id
allocation and would break an implementation that numbers differently for a good reason. Events are
rendered back through the reference instead. An execution id is written `@n`, the nth distinct one in
the stream, for the same reason.

Commands:

```
INSTRUMENT tick=5 lot=1 scale=4 min=1 max=1000000 band=500 open=100000 alloc=PRICE_TIME
SESSION    CONTINUOUS
NEW        BUY  LIMIT  GTC 100000 50
NEW        SELL LIMIT  IOC 100000 50  min=10
NEW        BUY  LIMIT  GTC  99995 100 display=10
NEW        SELL MARKET IOC       -  50 trigger=100500
NEW        BUY  LIMIT  GTC 100000 50  smp=7 p=2 POST_ONLY
CANCEL     #3
REPLACE    #3 40 100005
MASSCANCEL p=2
```

`INSTRUMENT` is required and comes first. On `NEW`, a price of `-` means the order has none, time in
force is abbreviated `GTC`, `DAY`, `IOC` and `FOK`, and `POST_ONLY` is the only flag. The four
qualifiers and the participant default to absent.

Events, one per line:

```
ACCEPTED   #1
REJECTED   #2 TICK_VIOLATION
RESTED     #1 BUY 100000 50
EXECUTED   @1 aggressor=#3 resting=#1 100000 50
REDUCED    #1 40
REMOVED    #1 50 CANCELLED
TRIGGERED  #4
STATE      CONTINUOUS
INDICATIVE 100000 500
```

A fixture holds both, with each event written under the command that caused it. Nothing marks which
is which, because no directive shares a name with a verb. That layout is for the reader: the
comparison is over the output lines in order, so where a fixture puts them changes nothing.

## The cross-language contract

An implementation in any language passes the corpus by reading the fixtures, replaying them, and
emitting these lines. The grammar is deliberately simple so that a runner is a small amount of code
in any language, and the fixtures are the contract rather than any one runner.

That is what makes a comparison between a Java engine and a C++ engine a measurement of two engines
and not of two readings of a specification.

## What has no test

If the compiler guarantees it, nothing is written. If it is a judgement about a future substitution,
it is reviewed. Both are recorded as such in `REQUIREMENTS.md`, so a reader asking why a line has no
test finds an answer instead of an omission.
