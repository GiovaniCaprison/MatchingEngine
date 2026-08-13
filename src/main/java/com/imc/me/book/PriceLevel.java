package com.imc.me.book;

import com.imc.me.domain.Order;

public interface PriceLevel {
  long price();

  long totalQty();

  Order first();

  boolean isEmpty();

  void add(final Order order);

  void remove(final Order order);

  void fillFirst(final long qty);

  void reduce(final Order order, final long qty);
}
