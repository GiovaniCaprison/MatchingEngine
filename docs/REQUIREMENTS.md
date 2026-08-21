# Requirements

What the engine must do. `SCOPE.md` says where its boundary is, `PROTOCOL.md` says what crosses it,
`PRINCIPLES.md` says why the code is shaped as it is, and `TESTING.md` and `METHODOLOGY.md` say how
correctness and performance are established.

Ids are cited from source comments. A comment reading `(FR-3.5)` means the code below it exists to
satisfy that line.

The mechanism column names how a requirement is shown to hold: `unit`, `corpus`, `property`,
`differential`, `benchmark`, `compiler` or `review`. `TESTING.md` defines each and what it can catch;
`METHODOLOGY.md` covers `benchmark`.

## FR-1: configuration and order entry

| Id | Requirement | Mechanism |
|---|---|---|
| FR-1.1 | An instrument definition command configures the engine, and precedes every other command | unit |
| FR-1.2 | Accepts a new order using every field the protocol defines for it | unit |
| FR-1.3 | An accepted order is assigned an engine order id, unique for the session, and reported | unit |
| FR-1.4 | A refused order is reported with a machine readable reason and changes no state | unit |

## FR-2: time in force and remainder

| Id | Requirement | Mechanism |
|---|---|---|
| FR-2.1 | A limit order's unmatched remainder rests at its own price | unit |
| FR-2.2 | A market order never rests | unit |
| FR-2.3 | An immediate-or-cancel remainder is removed | unit |
| FR-2.4 | A fill-or-kill order executes in full or not at all, and a kill leaves the book untouched | unit |
| FR-2.5 | A post-only order never takes liquidity, and is refused if it would | unit |
| FR-2.6 | An order carrying a minimum quantity executes at least that quantity on entry or is refused without executing | unit |

## FR-3: matching, allocation and self match prevention

| Id | Requirement | Mechanism |
|---|---|---|
| FR-3.1 | Resting liquidity is consumed best price first | unit |
| FR-3.2 | Within a price, allocation follows the algorithm configured for the instrument | unit |
| FR-3.3 | Price-time allocation consumes in arrival order | unit |
| FR-3.4 | Pro-rata allocation apportions in proportion to resting quantity, rounded down to lot, with the undistributed remainder allocated in arrival order | unit |
| FR-3.5 | An execution happens at the resting order's price | unit |
| FR-3.6 | Each execution reports an execution id, both order ids, price and quantity | unit |
| FR-3.7 | An order never executes against a resting order carrying the same non-zero self match id. The resting order is removed and the walk continues | unit |

## FR-4: amend and cancel

| Id | Requirement | Mechanism |
|---|---|---|
| FR-4.1 | A resting order can be cancelled by its engine order id | unit |
| FR-4.2 | Cancelling an order the engine is not resting is reported | unit |
| FR-4.3 | A replace carries the full intended new state rather than a delta | unit |
| FR-4.4 | A replace lowering quantity at the same price keeps queue position | unit |
| FR-4.5 | Any other replace loses queue position | unit |
| FR-4.6 | A replace refused by a liquidity flag leaves the original order resting | unit |
| FR-4.7 | A mass cancel removes every resting order for a participant and reports each removal | unit |

## FR-5: hidden quantity

| Id | Requirement | Mechanism |
|---|---|---|
| FR-5.1 | An order may display less than its total quantity | unit |
| FR-5.2 | Only displayed quantity appears as resting in the output stream | unit |
| FR-5.3 | Displayed quantity at a price is consumed before hidden quantity at that price | unit |
| FR-5.4 | When an order's displayed quantity is exhausted, a further tranche is displayed and joins the back of the queue at its price | unit |

## FR-6: stop orders

| Id | Requirement | Mechanism |
|---|---|---|
| FR-6.1 | A stop order rests in the trigger book and is not book liquidity | unit |
| FR-6.2 | A stop triggers when the last executed price reaches its trigger price, in the direction implied by its side | unit |
| FR-6.3 | A triggered stop enters the book as an order of its pricing instruction | unit |
| FR-6.4 | Triggers are evaluated after each execution, and a cascade runs to completion before the next command is applied | unit |
| FR-6.5 | A stop is reported on acceptance, on triggering and on cancellation | unit |

## FR-7: trading state and auctions

| Id | Requirement | Mechanism |
|---|---|---|
| FR-7.1 | The trading state changes only on a command, never on elapsed time | unit |
| FR-7.2 | The states are pre-open, opening auction, continuous, closing auction, halted and closed | unit |
| FR-7.3 | Order entry, replacement and cancellation are legal in every state except closed | unit |
| FR-7.4 | Continuous matching happens only in the continuous state | unit |
| FR-7.5 | An auction uncrosses at the price maximising executable volume, breaking ties by minimum surplus and then by proximity to the reference price | unit |
| FR-7.6 | An auction executes all matched quantity at one price | unit |
| FR-7.7 | An indicative uncrossing price and volume are reported whenever they change during a call phase | corpus |
| FR-7.8 | A halt cancels nothing, and the book is intact on resumption | unit |

## FR-8: output stream

| Id | Requirement | Mechanism |
|---|---|---|
| FR-8.1 | The events one command produced are identifiable as a group from the stream alone | unit |
| FR-8.2 | The output stream alone is sufficient to reconstruct the book at any point in it | corpus |
| FR-8.3 | An order entering the book is reported with side, price and displayed quantity | unit |
| FR-8.4 | An order leaving the book is reported with the quantity removed and the reason | unit |
| FR-8.5 | A quantity reduction that keeps queue position is reported without a removal | unit |

## VR: validity

| Id | Requirement | Mechanism |
|---|---|---|
| VR-1.1 | A non-positive quantity is refused | unit |
| VR-1.2 | A quantity off the instrument's lot size is refused rather than rounded | unit |
| VR-1.3 | A minimum quantity above the order quantity is refused | unit |
| VR-1.4 | A display quantity above the order quantity is refused | unit |
| VR-2.1 | A non-positive price on a priced order is refused | unit |
| VR-2.2 | A price off the instrument's tick size is refused rather than rounded | unit |
| VR-2.3 | A price outside the instrument's static band is refused | unit |
| VR-2.4 | A price outside the dynamic band is refused | unit |
| VR-3.1 | An inconsistent field combination is refused | unit |
| VR-4.1 | Every order type handles an empty book without corrupting it | unit |
| VR-5.1 | A refusal leaves the book unchanged | unit |

## NFR-1: determinism

| Id | Requirement | Mechanism |
|---|---|---|
| NFR-1.1 | The same input log produces the same output log, byte for byte | corpus |
| NFR-1.2 | The engine consumes input order rather than imposing it | review |
| NFR-1.3 | No behaviour depends on a clock, elapsed time or randomness | review |

## NFR-2: structure

| Id | Requirement | Mechanism |
|---|---|---|
| NFR-2.1 | Single writer: one thread mutates a book (P-2) | review |
| NFR-2.2 | The matching core depends only on the standard library | review |

## NFR-3: invariants

| Id | Requirement | Mechanism |
|---|---|---|
| NFR-3.1 | Aggregate resting quantity at a price equals the sum of the orders at it | property |
| NFR-3.2 | No empty price level and no unreferenced order remains | property |
| NFR-3.3 | The trigger book holds exactly the stops that have not fired | property |

## NFR-4: measurement

| Id | Requirement | Mechanism |
|---|---|---|
| NFR-4.1 | Submission is sub-linear in resting order count | benchmark |
| NFR-4.2 | Cancellation is constant time by order id | benchmark |
| NFR-4.3 | An implementation claiming a zero allocation steady state is measured, not trusted | benchmark |
| NFR-4.4 | Latency is reported as p50, p99, p99.9 and max, at a fixed offered rate, per command type | benchmark |
| NFR-4.5 | Every reported measurement names the implementation, the input parameters and the environment | benchmark |
| NFR-4.6 | Decode cost is attributed separately from matching cost | benchmark |

## NFR-5: comparability

| Id | Requirement | Mechanism |
|---|---|---|
| NFR-5.1 | Every implementation produces byte identical output for identical input | corpus, differential |
| NFR-5.2 | Every implementation passes the same corpus and the same unit suite | corpus, unit |
| NFR-5.3 | An implementation in another language passes the same corpus | corpus |

## Definitions

Three requirements above depend on terms that vary between venues, so they are fixed here.

The reference price is the last price executed in the current session. Before the first execution of
a session it is the opening reference configured on the instrument.

The dynamic band is a configured width either side of the reference price. VR-2.4 and VR-2.3 are both
applied; a price must satisfy the static band and the dynamic one.

Pro-rata apportionment rounds each participant's share down to a whole lot. Whatever remains
undistributed after rounding is allocated in arrival order, one lot at a time, until exhausted.
