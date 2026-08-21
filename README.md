# MatchingEngine

A limit order book matching engine, and a study of how much its implementation matters.

The engine is scoped to what a real matching engine owns, so it can later be a component of a
larger exchange. Anything belonging to a gateway, a risk engine or a market data publisher is out
of scope. The book holds resting limit orders; market, immediate-or-cancel and fill-or-kill orders
cross it without joining it.

The study measures successive implementations of that engine on identical input: what price level
indexing buys over a linear scan, what removing allocation and boxing buys on top of that, what a
cache friendly layout buys again, and where C++ overtakes a tuned JVM.

## Implementations

Each satisfies one interface and is measured on the same logs. Modules appear as they land.

| Rung | Book | Command handling |
|---|---|---|
| naive | one list, scanned | an order object per command |
| indexed | sorted price levels, index by order id | an order object per command |
| pooled | as indexed | pooled orders, no boxing, no steady state allocation |
| flyweight | flat price ladder, orders in a slab | fields read in place from the buffer |
| C++ | to be decided | separate process, same logs |

## Layout

```
matching-protocol/    the SBE schema, and the codecs generated from it
matching-api/         the interface every implementation satisfies
matching-naive/       rung 0
matching-benchmarks/  JMH, one implementation per fork
docs/
```

Each implementation is its own module for two reasons. Benchmarks need one implementation per JVM,
because two loaded together make the call site megamorphic and pollute every number after that. And
a conformance suite that depends only on `matching-api` cannot reach inside an implementation, so
black box testing holds without relying on discipline.

## Documentation

- [SCOPE.md](docs/SCOPE.md), where the engine ends and the exchange begins
- [PROTOCOL.md](docs/PROTOCOL.md), the commands and events that cross the boundary
- [REQUIREMENTS.md](docs/REQUIREMENTS.md), what the engine must do, and how each line is shown to hold
- [PRINCIPLES.md](docs/PRINCIPLES.md), why the code is shaped as it is. Read before changing a
  signature.

## Build

Requires JDK 21 and Maven.

```
mvn compile     build everything, generating the codecs first
mvn test        unit tests
```

Agrona reaches `jdk.internal.misc.Unsafe` for buffer access, so the build passes
`--add-exports java.base/jdk.internal.misc=ALL-UNNAMED`, declared once in the parent pom. Anything
embedding the engine needs the same flag.

Benchmarks are a shaded jar run outside Maven:

```
mvn package
java -jar matching-benchmarks/target/benchmarks.jar
```

They never run under `mvn test`. A wall clock assertion in a unit suite is flaky and measures nothing
that can be compared.

## Conventions

Commit messages read `(category): what I am actually doing`. Branches are one change each and land
through a pull request.
