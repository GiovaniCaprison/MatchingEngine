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
 * <p>For now, only LIMIT orders will be processed. This is so that I do not explode my skull with
 * so many new financial, design, and Java API concepts (e.g. POST IOC, LMAX, FFM/Unsafe).
 */
public enum OrderType {
  LIMIT,
  MARKET,
  IOC,
  FOK,
  POST
}
