package com.imc.me.matching;

import com.imc.me.book.BookSide;
import com.imc.me.domain.Order;
import com.imc.me.domain.Trade;
import java.util.List;

public interface Matcher {
  List<Trade> match(Order aggressor, BookSide opposing);
}
