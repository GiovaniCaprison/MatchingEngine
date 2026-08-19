package com.imc.me.matching;

import com.imc.me.book.BookSide;
import com.imc.me.book.Order;

/**
 * Price-then-time priority: best price first, FIFO within a price (FR-3.1, FR-3.2).
 *
 * <p>Not implemented yet. The book's three-phase dispatch, the boundary and the registry are all
 * wired around this, so submitting an order that would cross currently throws.
 */
public final class PriceTimeMatcher implements Matcher {

  @Override
  public void match(final Order aggressor, final BookSide opposing, final TradeSink sink) {
    throw new UnsupportedOperationException("PriceTimeMatcher.match not implemented yet");
  }

  @Override
  public long fillableQty(final Order aggressor, final BookSide opposing) {
    throw new UnsupportedOperationException("PriceTimeMatcher.fillableQty not implemented yet");
  }
}
