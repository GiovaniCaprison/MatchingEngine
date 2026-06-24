package com.imc.me.domain;

/**
 * The type of the order. Clients can choose any-one of the these.
 *
 * <ul>
 *   <li>Limit order — rests in the book at its price if not fully matched on entry.
 *   <li>Market order — matches against available liquidity regardless of price and MUST NOT rest in
 *       the book; any unfilled remainder MUST be handled per a defined policy (e.g. cancelled) that
 *       you state and enforce consistently.
 *   <li>Immediate-Or-Cancel (IOC) — match what is possible immediately, cancel any remainder.
 *   <li>Fill-Or-Kill (FOK) — execute in full immediately or not at all.
 *   <li>Post-only — never cross the spread; reject or reprice rather than take liquidity.
 * </ul>
 */
public enum OrderType {
  LIMIT,
  MARKET,
  IOC,
  FOK,
  POST
}
