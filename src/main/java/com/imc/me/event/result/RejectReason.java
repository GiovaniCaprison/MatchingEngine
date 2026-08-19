package com.imc.me.event.result;

/**
 * Why an order was refused. Machine-readable, so a client can react programmatically rather than by
 * parsing prose (API-1.2).
 *
 * <p>Split finely on purpose. TICK_VIOLATION tells a client to fix its price rounding and
 * FOK_UNFILLABLE tells it to retry smaller, where a single "invalid order" tells it nothing it can
 * act on.
 */
public enum RejectReason {

  /** VR-2.1: price was zero or negative. */
  NON_POSITIVE_PRICE,

  /** VR-1.1: quantity was zero or negative. Zero is not a small order, it is a meaningless one. */
  NON_POSITIVE_QTY,

  /** VR-2.2: price was not a whole multiple of the instrument's tick size. */
  TICK_VIOLATION,

  /** Quantity was not a whole multiple of the instrument's lot size, so it could not settle. */
  LOT_VIOLATION,

  /** Price was outside the instrument's static price band. */
  STP_VIOLATION,

  /** VR-3.2: side or type was missing or unrecognised, typically a bad wire decode. */
  UNKNOWN_ORDER_TYPE,

  /** FR-2.6: a POST-only order would have taken liquidity. Decided by the book's gate. */
  WOULD_CROSS,

  /** FR-2.5: an FOK order could not be filled in full. Also decided by the gate. */
  FOK_UNFILLABLE
}
