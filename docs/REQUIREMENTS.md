# Requirements

What the engine must do. `SCOPE.md` says where its boundary is, `PROTOCOL.md` says what crosses it,
and `PRINCIPLES.md` says why the code is shaped the way it is.

Ids are referenced from source comments. A comment reading `(FR-3.2)` means the code below it exists
to satisfy that line.

The mechanism column records how a requirement is meant to be shown to hold: a `unit` test, a
`corpus` fixture replayed and diffed, a `model` the engine is compared against, a `property` over
generated input, the `compiler`, a `benchmark`, or `review` where nothing else applies.

## FR: order entry

| Id | Requirement | Mechanism |
|---|---|---|
| FR-1.1 | Accepts a new order carrying side, pricing instruction, time in force, flags, price and quantity | unit |
| FR-1.2 | An accepted order is assigned an engine order id, unique for the session, and reported | unit |
| FR-1.3 | A refused order is reported with a machine readable reason and changes no state | unit |

## FR: resting and remainder

| Id | Requirement | Mechanism |
|---|---|---|
| FR-2.1 | A limit order's unmatched remainder rests at its own price | corpus |
| FR-2.2 | A market order never rests | corpus |
| FR-2.3 | An immediate-or-cancel remainder is removed rather than rested | corpus |
| FR-2.4 | A fill-or-kill order executes in full or not at all, and a kill leaves the book untouched | corpus |
| FR-2.5 | A post-only order never takes liquidity, and is refused if it would | corpus |

## FR: matching

| Id | Requirement | Mechanism |
|---|---|---|
| FR-3.1 | Resting liquidity is consumed best price first | corpus, model |
| FR-3.2 | Within a price, the earlier arrival is consumed first | corpus, model |
| FR-3.3 | An execution happens at the resting order's price | corpus, model |
| FR-3.4 | Each execution reports an execution id, both order ids, price and quantity | corpus |

## FR: amend and cancel

| Id | Requirement | Mechanism |
|---|---|---|
| FR-4.1 | A resting order can be cancelled by its engine order id | unit |
| FR-4.2 | Cancelling an order the engine is not resting is reported, not an error | unit |
| FR-4.3 | A replace carries the full intended new state rather than a delta | corpus |
| FR-4.4 | A replace lowering quantity at the same price keeps queue position | corpus, model |
| FR-4.5 | Any other replace loses queue position | corpus, model |
| FR-4.6 | A replace refused by a liquidity flag leaves the original order resting | corpus |

## FR: output stream

| Id | Requirement | Mechanism |
|---|---|---|
| FR-5.1 | Every event carries its own output sequence and the input sequence that caused it | corpus |
| FR-5.2 | The output stream alone is sufficient to reconstruct the book at any point in it | corpus |
| FR-5.3 | An order entering the book is reported with side, price and resting quantity | corpus |
| FR-5.4 | An order leaving the book is reported with the quantity removed and the reason | corpus |
| FR-5.5 | A quantity reduction that keeps queue position is reported without a removal | corpus |

## VR: validity

| Id | Requirement | Mechanism |
|---|---|---|
| VR-1.1 | A non-positive quantity is refused | unit |
| VR-1.2 | A quantity off the instrument's lot size is refused rather than rounded | unit |
| VR-2.1 | A non-positive price on a priced order is refused | unit |
| VR-2.2 | A price off the instrument's tick size is refused rather than rounded | unit |
| VR-2.3 | A price outside the instrument's band is refused | unit |
| VR-3.1 | An unknown or inconsistent field combination is refused | unit |
| VR-4.1 | Every order type handles an empty book without corrupting it | corpus |
| VR-5.1 | A refusal leaves the book byte identical | unit |

## NFR: correctness under load

| Id | Requirement | Mechanism |
|---|---|---|
| NFR-1.1 | The same input log produces the same output log, byte for byte | corpus |
| NFR-1.2 | The engine consumes input order rather than imposing it | review |
| NFR-2.1 | Single writer: one thread mutates a book (P-2) | review |
| NFR-3.1 | Aggregate resting quantity at a price equals the sum of the orders at it | property |
| NFR-3.2 | No empty price level and no unreferenced order remains | property |
| NFR-4.1 | The matching core depends only on the JDK | review |

The matching core means the book and the matching logic. A protocol module may depend on a codec
generator; hand writing a wire format is a separate project.

## NFR: measurement

| Id | Requirement | Mechanism |
|---|---|---|
| NFR-5.1 | Submission is sub-linear in resting order count | benchmark |
| NFR-5.2 | Cancellation is constant time by order id | benchmark |
| NFR-5.3 | An implementation claiming a zero allocation steady state is measured, not trusted | benchmark |
| NFR-5.4 | Latency is reported as p50, p99, p99.9 and max, at a fixed offered rate, per command type | benchmark |
| NFR-5.5 | Every reported measurement names the implementation, the input parameters and the environment | benchmark |
| NFR-5.6 | Decode cost is attributed separately from matching cost | benchmark |

Decode is part of an implementation and therefore part of its cost. Without NFR-5.6 the difference
between two books can be swamped by the difference between two decoders.

## NFR: comparability

| Id | Requirement | Mechanism |
|---|---|---|
| NFR-6.1 | Every implementation produces byte identical output for identical input | corpus |
| NFR-6.2 | Every implementation passes the same corpus and the same unit suite | corpus, unit |
| NFR-6.3 | An implementation in another language passes the same corpus | corpus |

## Open

Self match prevention. Participant id is on the wire and nothing reads it. The policy has to be
chosen first: refuse the aggressor, cancel the resting order, cancel both, or decrement both.

Session state. A real engine owns pre-open, open, halt and close, and auction matching is a
different algorithm from continuous matching. The schema leaves room for it and nothing implements
it.

Fairness beyond price and time. Pro-rata and size pro-rata are real venue policies. Nothing here
says what either should do.

Price bands. VR-2.3 names the requirement. What the band is relative to, a static reference or a
dynamic one, is undecided.
