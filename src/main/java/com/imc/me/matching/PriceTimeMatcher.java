package com.imc.me.matching;

import com.imc.me.book.BookSide;
import com.imc.me.domain.Order;
import com.imc.me.domain.Trade;
import java.util.List;

public final class PriceTimeMatcher implements Matcher {
  public List<Trade> match(Order aggressor, BookSide opposing) {
    throw new UnsupportedOperationException("PriceTimeMatcher.match not implemented yet");
  }
}
