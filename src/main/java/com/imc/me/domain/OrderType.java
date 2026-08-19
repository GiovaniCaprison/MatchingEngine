package com.imc.me.domain;

/**
 * The type of the order. Clients can choose any-one of the these.
 *
 * <ul>
 *   <li>Limit order - rests in the book at its price if not fully matched on entry.
 *   <li>Market order - matches against available liquidity regardless of price and MUST NOT rest in
 *       the book; any unfilled remainder MUST be handled per a defined policy (e.g. cancelled) that
 *       you state and enforce consistently.
 *   <li>Immediate-Or-Cancel (IOC) - match what is possible immediately, cancel any remainder.
 *   <li>Fill-Or-Kill (FOK) - execute in full immediately or not at all.
 *   <li>Post-only - never cross the spread; reject or reprice rather than take liquidity.
 * </ul>
 *
 * An important nuance which help me model this personally is:
 *
 * <ul>
 *   <li>Market / Limit = pricing instruction
 *   <li>IOC / FOK / GTC / DAY = time-in-force / execution constraint
 *   <li>Post-only = liquidity constraint
 * </ul>
 *
 * Therefor at some point I will intentionally allow for LIMIT IOC, MARKET IOC, LIMIT... etc
 * combinations. For now we pick a default per order-type and run with that because the scope of the
 * project initially is big enough already.
 *
 * <p>That instinct is right, and it is what production engines and FIX both do: {@code OrdType<40>}
 * for the pricing instruction, {@code TimeInForce<59>} for the execution constraint, {@code
 * ExecInst<18>} for the liquidity constraint. Read this enum as a <b>profile</b> — a named point in
 * that three-axis space, where {@code IOC} means {@code (LIMIT, IOC, none)}. The migration to three
 * narrow fields expands the profile at the validation boundary and touches nothing else, because
 * these are <i>data on a flat layout</i> rather than subtypes (OOD-8). Had they been subtypes, the
 * same change would demand a {@code MarketIocPostOrder} and the combinatorial explosion that kills
 * the design.
 *
 * <p><b>Where each type is actually handled.</b> Nowhere in the matching walk — the walk is identical
 * for all five. Only the gate before it and the remainder policy after it differ, both of which are
 * switches in {@code TreeMapOrderBook.submit}:
 *
 * <table border="1">
 *   <caption>Per-type policy</caption>
 *   <tr><th>Type</th><th>Gate (pre-trade)</th><th>Remainder (post-trade)</th></tr>
 *   <tr><td>LIMIT</td><td>—</td><td>rests (FR-2.1)</td></tr>
 *   <tr><td>MARKET</td><td>—</td><td>cancelled; never rests (FR-2.2, FR-2.3)</td></tr>
 *   <tr><td>IOC</td><td>—</td><td>cancelled (FR-2.4)</td></tr>
 *   <tr><td>FOK</td><td>killed unless fully fillable (FR-2.5)</td><td>unreachable</td></tr>
 *   <tr><td>POST</td><td>rejected if it would cross (FR-2.6)</td><td>rests</td></tr>
 * </table>
 *
 * <p>Adding a constant here fails compilation at both switches until it is handled, which is how you
 * find every place that has to decide something about it.
 */
public enum OrderType {
  LIMIT,
  MARKET,
  IOC,
  FOK,
  POST
}
