# Requirements

The specification, as a flat list. `ENGINEERING_GUIDE.md` says how the engine is built,
`OOD_PRINCIPLES.md` says why it is shaped the way it is, and `TESTING.md` says how any of it
gets proved.

Ids are referenced from javadoc throughout the source. A comment reading `(FR-3.5)` means
the code below it exists to satisfy that line in this file.

The "proved by" column records the intended mechanism: a `unit` example, a `scenario` fixture, a
`property` over random input, a `model` the engine is diffed against, a structural `rule`, the
`compiler`, a `benchmark`, a `soak` run, the `simulation` that feeds those two, or `conformance`
against an independent implementation. `open` is an honest answer where nothing proves it yet.

## FR: functional

| Id | Requirement | Proved by |
|---|---|---|
| FR-1.1 | Accepts orders specifying side, type, quantity and price | unit |
| FR-1.2 | Rejects invalid orders and communicates the reason | unit |
| FR-1.3 | Accepted orders are assigned a uid returned to the client | unit |
| FR-2.1 | A limit order rests at its price if it is not fully matched | scenario |
| FR-2.2 | A market order never rests | scenario |
| FR-2.3 | A market order's unfilled remainder is cancelled | scenario |
| FR-2.4 | IOC matches available liquidity, remainder cancelled | scenario |
| FR-2.5 | FOK executes in full immediately or not at all | scenario |
| FR-2.6 | POST never takes liquidity | scenario |
| FR-3.1 | Matching follows price priority, best price first | scenario, model |
| FR-3.2 | Time priority at equal prices, FIFO | scenario, model |
| FR-3.3 | An aggressing order matches resting liquidity | scenario, model |
| FR-3.4 | A trade record is produced per match, carrying both uids, quantity and price | scenario |
| FR-3.5 | Price improvement accrues to the aggressor at the resting price | scenario, model |
| FR-4.1 | A resting order can be cancelled by its uid | unit |
| FR-4.2 | Cancellation is idempotent; a re-cancel fails explicitly | unit |
| FR-4.3 | A resting order's quantity and price can be amended | scenario |
| FR-4.4 | An amend raising quantity or changing price loses time priority | scenario, model |
| FR-4.5 | An amend only lowering quantity keeps time priority | scenario, model |
| FR-5.1 | Best bid and ask are exposed with price and aggregate quantity | unit |
| FR-5.2 | An empty side is clearly indicated | unit |
| FR-5.3 | Depth aggregation matches per-level resting totals | property |
| FR-5.4 | Order state is retrievable by uid, including remaining quantity | unit |
| FR-5.5 | Query methods return only immutable types | rule |
| FR-6.1 | The engine emits acceptance, fills, placement and terminal state | scenario |

## VR: validity

| Id | Requirement | Proved by |
|---|---|---|
| VR-1.1 | Zero or negative quantity is rejected | unit |
| VR-2.1 | A non-positive price is rejected | unit |
| VR-2.2 | An off-tick or over-precision price is rejected | unit |
| VR-3.1 | Every order type handles an empty book without corrupting it | scenario |
| VR-3.2 | An unknown or malformed order type is rejected | unit |
| VR-4.1 | A full sweep across several levels stays consistent | scenario |
| VR-4.2 | A sweep that exhausts a side leaves a clean empty book | scenario |
| VR-6.1 | Per-level resting totals reconcile with the raw orders | property |

## NFR: non-functional

| Id | Requirement | Proved by |
|---|---|---|
| NFR-1.1 | Identical input produces identical trades | scenario |
| NFR-1.2 | Identical input produces an identical final book | scenario |
| NFR-2.1 | Submission is sub-linear in resting order count | benchmark |
| NFR-2.2 | Cancellation is constant time by uid | benchmark |
| NFR-2.3 | A top-of-book query is constant time per side | benchmark |
| NFR-3.1 | Aggregate depth equals the sum of resting quantity after any stream | property |
| NFR-3.2 | No empty levels and no orphaned orders remain | property, unit |
| NFR-4.1 | Single writer: book mutation is confined to one package | rule |
| NFR-5.1 | The core engine depends only on itself and the JDK | rule |
| NFR-6.1 | Internal invariants hold under randomised load | property |
| NFR-7.1 | A steady-state submit on the core path allocates zero bytes | benchmark |
| NFR-7.2 | Per-command allocation at the public boundary is measured separately from the core | benchmark |
| NFR-8.1 | Invariants hold, and latency is reported, with a book of 10^6 resting orders | soak |
| NFR-8.2 | Book size is stationary under generated flow, with insert and cancel rates balanced | soak |
| NFR-9.1 | A seeded generator produces an identical command log on every run | simulation |
| NFR-9.2 | Generated flow is parameterised by arrival, placement, size, type mix and cancel ratio, and every reported result names the parameters it was measured against | simulation |
| NFR-9.3 | Any generated run can be written out as a scenario fixture and replayed | simulation |
| NFR-10.1 | The engine agrees with a reference implementation over generated flow | model |
| NFR-11.1 | Latency is reported as p50, p99, p99.9 and max, at a fixed offered rate, per command type | benchmark |
| NFR-12.1 | An independent implementation passes the same scenario corpus | conformance |

## API

| Id | Requirement | Proved by |
|---|---|---|
| API-1.1 | `submit` returns a typed outcome carrying the uid and any fills | unit |
| API-1.2 | A rejection carries a machine-readable reason | unit |
| API-1.3 | The client order id is echoed back on the outcome | unit |
| API-2.1 | `cancel` returns found or not-found and never throws | unit |
| API-3.1 | `amend` applies the documented priority semantics | scenario |
| API-4.1 | The top-of-book query returns price and aggregate quantity | unit |
| API-5.1 | The depth query returns levels in priority order | unit |
| API-6.1 | The status query returns an order's current state by uid | unit |
| API-7.1 | An event consumer can register and receive events | unit |
| API-8.1 | Invalid input is refused at the boundary | unit, rule |
| API-8.2 | A boundary rejection leaves the book unmodified | unit |
| API-9.1 | Acceptance and rejection are distinguishable types | unit |
| API-10.1 | The public API is the only entry point that can mutate the book | compiler |
| API-11.1 | No public method returns a mutable collection | rule |

## Open

Four things this list does not yet say, recorded so the silence is deliberate.

Self-trade policy. Whether an order may match against resting quantity from the same
participant, and whether the resolution is cancel-newest, cancel-oldest or cancel-both. There
is no participant identity in the model yet, so the requirement waits on that.

Event stream completeness. FR-6.1 says which events are emitted. It does not say the stream
is sufficient on its own to rebuild the book, which is the property a downstream consumer
actually depends on and which is invisible in-process when it breaks.

Fairness beyond price and time. Price-time is the only priority rule specified. Pro-rata and
size-pro-rata are real venue policies, and `Matcher` exists so one can be substituted, but no
requirement says what either should do.
