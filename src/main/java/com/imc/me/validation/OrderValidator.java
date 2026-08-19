package com.imc.me.validation;

import com.imc.me.domain.Instrument;
import com.imc.me.domain.OrderType;
import com.imc.me.event.command.NewOrder;
import com.imc.me.event.result.RejectReason;

/**
 * The boundary. Everything below this line assumes valid input and never re-checks (OOD-5).
 *
 * <p>The obvious reason validation lives in one place is cost: a tick-size check inside the
 * matching walk would run millions of times per second to re-establish something already known. The
 * reason that matters more is that scattered validation makes "was the book modified?"
 * unanswerable. If the book validated too, some rejections would land after partial mutation and
 * rejection would have to become transactional. Checking strictly before touching state makes
 * API-8.2 true by construction.
 *
 * <p>The consequence is a rule that looks wrong until you know why: an {@code if (qty <= 0) throw}
 * in {@code book} or {@code matching} is a defect even though it looks defensive. It means the
 * boundary is not trusted, and it costs latency on every order to catch a case that cannot occur.
 *
 * <p>Stateless and instrument-driven, so validating against a different instrument is a different
 * argument rather than a different code path.
 */
public final class OrderValidator {

  private OrderValidator() {}

  /**
   * Checks a new-order request.
   *
   * @return the reason it must be refused, or {@code null} if it may proceed. Null-as-valid rather
   *     than an {@code Optional} or a result object because this runs once per inbound order and
   *     the happy path must not allocate (OOD-11).
   */
  public static RejectReason validate(final NewOrder order, final Instrument instrument) {
    // VR-3.2: a malformed request, typically a missing enum from a bad wire decode, is refused here
    // rather than left to NPE somewhere less obvious later.
    if (order.side() == null || order.type() == null) return RejectReason.UNKNOWN_ORDER_TYPE;

    // VR-1.1: zero is not a small order, it is a meaningless one; negative is a wire bug.
    if (order.qty() <= 0) return RejectReason.NON_POSITIVE_QTY;

    // A quantity off the lot size cannot settle, so it is refused rather than rounded. Rounding
    // would silently trade a quantity the client did not ask for.
    if (instrument.lotSize() > 0 && order.qty() % instrument.lotSize() != 0) {
      return RejectReason.LOT_VIOLATION;
    }

    // A MARKET order has no price to check, since the boundary replaces it with a sentinel so the
    // walk needs no special case (see Prices). Checking the client's price field here would refuse
    // a perfectly good market order for carrying a leftover zero.
    if (order.type() == OrderType.MARKET) return null;

    // VR-2.1
    if (order.price() <= 0) return RejectReason.NON_POSITIVE_PRICE;

    // VR-2.2: off-tick and over-precision are the same check once prices are scaled longs, which is
    // one of the quieter payoffs of never using a double for a price (OOD-12).
    if (instrument.tickSize() > 0 && order.price() % instrument.tickSize() != 0) {
      return RejectReason.TICK_VIOLATION;
    }

    return null;
  }
}
