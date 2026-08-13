package com.imc.me.support;

import com.imc.me.book.BookSide;
import com.imc.me.book.Order;
import com.imc.me.matching.Matcher;
import com.imc.me.matching.TradeSink;

/**
 * A matcher that never crosses: every order rests, nothing executes.
 *
 * <p>Lets tests exercise the parts of the engine that are not the matching algorithm -- validation,
 * identity, the registry, event fan-out, the remainder policies -- without the walk existing. That is
 * only possible because the walk is a strategy the book drives rather than logic baked into it.
 */
public final class NoCrossMatcher implements Matcher {

  @Override
  public void match(final Order aggressor, final BookSide opposing, final TradeSink sink) {}

  @Override
  public long fillableQty(final Order aggressor, final BookSide opposing) {
    return 0L;
  }
}
