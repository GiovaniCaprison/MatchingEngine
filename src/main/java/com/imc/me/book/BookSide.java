package com.imc.me.book;

import com.imc.me.domain.Order;
import com.imc.me.domain.OrderSide;
import com.imc.me.event.dto.Depth;
import java.util.List;

/**
 * One side of the book. Owns its price levels AND its id index, so an order's membership in a side
 * is managed entirely by that side: {@link #addOrder} registers it, {@link #remove} deregisters it.
 * The Matcher mutates a side only through these methods, which is what keeps the id index and the
 * {@code totalQty} invariant (VR-6.1) consistent without the matcher ever touching a map.
 */
public interface BookSide {
  OrderSide side();

  boolean isEmpty();

  Order get(long orderId);

  PriceLevel bestLevel();

  List<Depth.Level> depth();

  void addOrder(Order order);

  void remove(Order order);
}
