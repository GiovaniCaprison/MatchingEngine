package com.imc.me.book;

import com.imc.me.domain.Order;

public sealed interface PriceLevel permits LinkedListPriceLevel {
  long totalQty();

  Order first();

  boolean isEmpty();

  void add(Order order);

  void remove(Order order);

  void fillFirst(long qty);

  void reduce(Order order, long qty);
}
