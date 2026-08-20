# MatchingEngine

A limit order book matching engine, built as a component of an exchange rather than as a
demonstration, and used as the subject of a performance study.

There are two goals and they pull in the same direction. The first is an engine I can drop into a
larger exchange project later, which means its boundary has to be the boundary a real matching
engine has, and it has to do nothing that belongs to a gateway, a risk engine or a market data
publisher. The second is to find out how much the implementation of that engine actually matters:
what price level indexing buys over a linear scan, what removing allocation and boxing buys on top of
that, what a flat cache friendly layout buys on top of that, and at what point C++ is simply the
better language rather than assumed to be.

## How the study works

The engine is a deterministic function from an ordered log of commands to an ordered log of events.
That single property is what makes the whole thing measurable: two implementations fed the same log
must produce the same events, byte for byte, so a comparison between them is a measurement rather
than an argument.

So there is one message protocol and one interface, and every implementation is free to do whatever
it likes behind it. Decoding a command is part of an implementation, which means it is part of that
implementation's cost. An engine that copies each command into fresh objects pays for that. An engine
that reads fields in place out of the buffer does not. Both are designs real systems ship, and the
difference between them is one of the things being measured rather than something to factor out.

Implementations, from the bottom up:

- naive: a list, scanned, with a new order object per command
- indexed: price levels in a sorted structure, an id index, objects per command
- pooled: the same asymptotics with no boxing and no steady state allocation
- flyweight: fields read in place from the buffer, orders in a flat ladder
- C++: the same protocol, in a separate process, fed the same log

Every one of them replays the same corpus of scenarios and has to produce identical output. The
corpus is plain text and the grammar is deliberately simple, so an implementation in any language can
be held to it.

For measurement, each implementation runs in its own JVM. Two implementations loaded together make
the call site megamorphic and every number after that is polluted, which is also why they are
separate modules. The C++ process is fed the log once and times itself internally, so the comparison
is engine against engine rather than network stack against network stack.

## Layout

```
protocol/         the SBE schema and the generated codecs
engine-api/       the interface every implementation satisfies
engine-naive/     a list, scanned
conformance/      the scenario corpus and the runner, which depends only on engine-api
benchmarks/       JMH, one implementation per fork
cpp/              the C++ implementation, built separately
docs/
```

`conformance` depending only on `engine-api` is deliberate: a fixture physically cannot reach inside
an implementation, so black box testing is enforced by the compiler rather than by discipline.

## Documentation

- [SCOPE.md](docs/SCOPE.md), where the engine ends and the exchange begins, including what it
  deliberately does not do
- [PROTOCOL.md](docs/PROTOCOL.md), the commands and events that cross the boundary, and what they mean
- [REQUIREMENTS.md](docs/REQUIREMENTS.md), what the engine must do, and how each requirement is shown
  to hold
- [PRINCIPLES.md](docs/PRINCIPLES.md), why the code is shaped the way it is. Read this before
  changing a signature.

## Build

Requires JDK 21 and Maven.

```
mvn compile     build everything
mvn test        unit tests and the conformance corpus
```

Benchmarks do not run under `mvn test` and never should. A wall clock assertion inside a unit suite
is the flakiest thing available and it contradicts the point of the project.

## Conventions

Commit messages read `(category): what I am actually doing`. Branches are one change each and land
through a pull request.
