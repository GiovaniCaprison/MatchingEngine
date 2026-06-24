# OrderBook Engine — Requirements Specification

This document defines **what** must be built and the behaviour it must exhibit. It deliberately does not describe how to implement anything. Treat every "MUST" as a hard requirement and every "SHOULD" as a graded/stretch requirement.

## 1. Product Summary

Build a limit order book matching engine in Java for a single instrument (one symbol). The engine accepts orders, maintains resting liquidity, matches incoming orders against the book under a deterministic priority rule, and emits the resulting events. It is a library/engine, not a UI.

## 2. Scope

### In scope
* A single-symbol, in-memory order book.
* Order intake (submit), cancellation, and amendment.
* Matching of marketable orders against resting orders.
* Emission of trade and book-state events.
* Queryable top-of-book and depth.

### Out of scope (unless attempted as a stretch goal)
* Multi-symbol routing, accounts/balances, settlement, fees.
* Network transport, authentication, persistence to disk.
* Wall-clock scheduling, auctions, circuit breakers.

## 3. Definitions

* **Order** — an intent to buy (bid) or sell (ask) a quantity at a constraint defined by its type.
* **Resting order** — an order that remains in the book awaiting a match.
* **Marketable order** — an incoming order that can immediately match against the opposite side.
* **Top of book** — the best bid and best ask currently resting.
* **Spread** — best ask price minus best bid price.
* **Fill** — a quantity executed for an order; an order may have many partial fills.
* **Trade** — a single match event between exactly one aggressing order and one resting order.

## 4. Functional Requirements

### 4.1 Order submission
* FR-1 The engine MUST accept new orders specifying at minimum: side, order type, quantity, and (where applicable) price.
* FR-2 The engine MUST reject orders that violate validity rules (see §5) and MUST communicate the rejection reason distinctly from acceptance.
* FR-3 Every accepted order MUST be assigned a unique, engine-generated identifier that is returned to the caller.
* FR-4 The engine MUST process orders in the order they are received and MUST produce deterministic results for a given input sequence.

### 4.2 Order types
The engine MUST support:
* FR-5 **Limit order** — rests in the book at its price if not fully matched on entry.
* FR-6 **Market order** — matches against available liquidity regardless of price and MUST NOT rest in the book; any unfilled remainder MUST be handled per a defined policy (e.g. cancelled) that you state and enforce consistently.

The engine SHOULD support, as graded extensions:
* FR-7 **Immediate-Or-Cancel (IOC)** — match what is possible immediately, cancel any remainder.
* FR-8 **Fill-Or-Kill (FOK)** — execute in full immediately or not at all.
* FR-9 **Post-only** — never cross the spread; reject or reprice rather than take liquidity.

### 4.3 Matching
* FR-10 Matching MUST follow strict **price priority**: better-priced resting orders are matched first.
* FR-11 At equal price, matching MUST follow **time priority**: earlier-resting orders are matched first.
* FR-12 An aggressing order MUST be matched against as many resting orders as needed until it is exhausted or no further price-compatible liquidity exists.
* FR-13 Each individual match MUST produce a trade record identifying both participating orders, the executed quantity, and the execution price.
* FR-14 The execution price of a trade MUST be the resting order's price (price improvement accrues to the aggressor), and this rule MUST be applied consistently.

### 4.4 Cancellation and amendment
* FR-15 The engine MUST allow cancellation of a resting order by its identifier.
* FR-16 Cancelling a non-existent or already-removed order MUST fail explicitly without corrupting book state.
* FR-17 The engine MUST allow amendment of a resting order's quantity and/or price.
* FR-18 An amendment that increases quantity or changes price MUST lose time priority per a stated, consistent rule; an amendment that only reduces quantity SHOULD retain time priority.

### 4.5 Querying
* FR-19 The engine MUST expose the current best bid and best ask (price and aggregate quantity), or clearly indicate when a side is empty.
* FR-20 The engine MUST expose aggregated depth: for each side, price levels with total resting quantity, ordered by priority.
* FR-21 The engine MUST allow retrieval of the current state of an individual order by identifier (remaining quantity and status).
* FR-22 All query operations MUST be read-only and MUST NOT mutate book state.

### 4.6 Events / outputs
* FR-23 The engine MUST emit, for each accepted order, a sequence of observable outcomes sufficient to reconstruct what happened: acceptance, each fill, resting placement, and terminal state (filled / cancelled / rejected / expired).
* FR-24 Emitted events MUST carry enough information for an external consumer to compute positions and trade history without inspecting engine internals.

## 5. Validity & Edge-Case Requirements

* VR-1 Quantity MUST be strictly positive; zero or negative quantity MUST be rejected.
* VR-2 Limit price MUST be strictly positive and conform to a defined tick/precision rule; violations MUST be rejected.
* VR-3 The engine MUST behave correctly against an empty book (e.g. a market order with no liquidity, a marketable limit with nothing to hit).
* VR-4 The engine MUST correctly handle an aggressing order larger than all available liquidity (full sweep, then rest or cancel per type).
* VR-5 Self-trade behaviour (if order ownership is modelled) MUST follow a stated, consistent policy. If ownership is not modelled, this MUST be explicitly declared out of scope.
* VR-6 No operation may leave the book in an inconsistent state: aggregate depth MUST always equal the sum of resting order quantities at each level.

## 6. API / Contract Requirements

The engine MUST expose a clearly defined public API (a Java interface boundary) that satisfies the contracts below. The signatures, types, and structure are yours to design — but the API MUST make the following operations and guarantees available and MUST hide all internal data structures.

The public API MUST provide:
* API-1 An operation to **submit** a new order that returns either an accepted result (with the assigned identifier and any immediate fills) or a rejection (with a reason). The result MUST be returned synchronously.
* API-2 An operation to **cancel** an order by identifier that distinguishes success from "not found / not cancellable".
* API-3 An operation to **amend** an order by identifier with the priority semantics of FR-18.
* API-4 A **top-of-book** query (API-19 behaviour).
* API-5 A **depth** query returning aggregated levels per side (FR-20 behaviour).
* API-6 An **order status** query by identifier (FR-21 behaviour).
* API-7 A mechanism for an external consumer to **observe events** (FR-23 / FR-24) — e.g. a subscription/callback contract — without polling internal state.

API contract guarantees:
* API-8 All inputs MUST be validated at the boundary; invalid input MUST never reach matching logic.
* API-9 Error and rejection conditions MUST be represented as typed, distinguishable outcomes — not as silent failures or ambiguous return values.
* API-10 The contract MUST be safe to call repeatedly and MUST document (in code) any ordering or threading expectations it imposes on callers.
* API-11 No public API method may expose mutable internal collections or allow external mutation of book state outside the defined operations.

## 7. Non-Functional Requirements

* NFR-1 **Determinism** — identical input sequences MUST yield identical outputs and identical final book state.
* NFR-2 **Performance** — submit, cancel, and top-of-book queries SHOULD operate at sub-linear cost relative to the number of resting orders; you MUST be able to state and justify the complexity of each core operation.
* NFR-3 **Correctness under volume** — the engine MUST remain correct and stable under a high-volume randomized order stream (no leaks, no invariant violations).
* NFR-4 **Thread-safety** — the concurrency model MUST be explicitly stated. Either the engine is single-threaded by contract, or it MUST guarantee correctness under concurrent access; ambiguity is not acceptable.
* NFR-5 **No external dependencies** for the core engine beyond the standard library, unless justified.
* NFR-6 **Observability** — internal invariants (e.g. VR-6) MUST be assertable/verifiable for testing.

## 8. Acceptance Criteria

The project is considered complete when:
* AC-1 All MUST functional and validity requirements are demonstrably met.
* AC-2 A test suite exercises: price priority, time priority, partial fills, full sweeps, each supported order type, cancellation, amendment with priority changes, and every edge case in §5.
* AC-3 Book invariants (§6 / VR-6) hold after every operation in a randomized stress test.
* AC-4 The complexity of each core operation is stated and consistent with NFR-2.
* AC-5 The concurrency contract (NFR-4) is stated and, if concurrent, verified.
* AC-6 The public API satisfies every contract in §6 with no leakage of internal state.

## 9. Stretch Goals (graded extra)

* SG-1 Support IOC, FOK, and post-only (FR-7–FR-9).
* SG-2 A market-data event stream sufficient to rebuild the book externally from events alone.
* SG-3 Configurable self-trade prevention.
* SG-4 A benchmark harness reporting throughput and per-operation latency distribution.
* SG-5 Snapshot + event-replay capability to reconstruct state.

## 10. Deliverables

* D-1 The engine source with a clearly delineated public API boundary.
* D-2 The test suite covering §8.
* D-3 A short written statement of: chosen unfilled-remainder policy (FR-6), amendment priority rule (FR-18), self-trade policy (VR-5), concurrency contract (NFR-4), and per-operation complexity (NFR-2).

