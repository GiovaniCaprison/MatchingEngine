package com.imc.me.domain;

/**
 * The pricing and execution profile of an order.
 *
 * <ul>
 *   <li>{@code LIMIT} rests at its price if it is not fully matched on entry (FR-2.1).
 *   <li>{@code MARKET} takes available liquidity at any price and never rests; its remainder is
 *       cancelled (FR-2.2, FR-2.3).
 *   <li>{@code IOC} matches what it can immediately and cancels the remainder (FR-2.4).
 *   <li>{@code FOK} executes in full immediately or not at all (FR-2.5).
 *   <li>{@code POST} never takes liquidity, and is rejected rather than allowed to cross (FR-2.6).
 * </ul>
 *
 * <p>These five are profiles over three axes that FIX keeps separate: pricing instruction
 * ({@code OrdType<40>}), time in force ({@code TimeInForce<59>}) and liquidity constraint
 * ({@code ExecInst<18>}). {@code IOC} here means {@code (LIMIT, IOC, none)}. Splitting them into
 * three narrow fields later expands the profile at the validation boundary and leaves the matching
 * walk untouched, because the variation is data on a flat layout (OOD-8).
 *
 * <p>The walk is identical for all five types. Only the gate before it and the remainder policy
 * after it differ, both of them switches in {@code TreeMapOrderBook.submit}. Neither switch has a
 * default arm, so a new constant here fails compilation until every decision point handles it.
 */
public enum OrderType {
  LIMIT,
  MARKET,
  IOC,
  FOK,
  POST
}
