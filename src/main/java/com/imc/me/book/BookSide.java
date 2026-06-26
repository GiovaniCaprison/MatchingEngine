package com.imc.me.book;

import com.imc.me.domain.Order;
import com.imc.me.domain.OrderSide;
import java.util.List;

public sealed interface BookSide permits TreeMapBookSide {
  boolean isEmpty();

  OrderSide side();

  PriceLevel bestLevel();

  PriceLevel levelAt(long price);

  List<PriceLevel> depth();

  void addOrder(Order order);

  void removeLevelIfEmpty(long price);
}
