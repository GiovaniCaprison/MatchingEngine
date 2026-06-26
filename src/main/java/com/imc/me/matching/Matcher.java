package com.imc.me.matching;

import com.imc.me.book.OrderBook;
import com.imc.me.domain.Order;
import com.imc.me.event.result.MatchResult;

public sealed interface Matcher permits PriceTimeMatcher {
  MatchResult match(Order aggressor, OrderBook book);
}
