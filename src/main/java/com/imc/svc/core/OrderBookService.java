package com.imc.svc.core;

import com.imc.svc.model.Order;
import com.imc.svc.model.OrderNode;
import com.imc.svc.model.OrderSide;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

public class OrderBookService {
  private static final TreeMap<Long, OrderNode<Order>> buySideBook = new TreeMap<>();
  private static final TreeMap<Long, OrderNode<Order>> sellSideBook = new TreeMap<>();
  private static final Map<Long, OrderNode<Order>> orderMap = new HashMap<>();

  /**
   * Place a new Order on the Order Book
   *
   * @param order the order to place
   * @return True if the order is placed successfully. False otherwise
   */
  public static boolean placeOrder(final Order order) {
    if (order.orderSide() == OrderSide.BUY) {
      return matchBuyOrder(order) ? true : restBuyOrder(order);
    } else {
      return matchSellOrder(order) ? true : restSellOrder(order);
    }
  }

  /**
   * Cancel an existing order on the Order Book
   *
   * @param uid the unique identifier of the Order to cancel
   */
  public static void cancelOrder(final long uid) {
    OrderNode<Order> orderNode = orderMap.get(uid);
    removeOrder(orderNode);
  }

  /**
   * @param order the Buy Order to match against the SellSide Book
   * @return True if successully matched. False otherwise
   */
  private static boolean matchBuyOrder(final Order order) {
    long priceLevel = order.price();

    OrderNode<Order> sellOrderHead = sellSideBook.get(priceLevel);
    if (sellOrderHead == null || sellOrderHead.data == null) {
      return false;
    }

    boolean isBuyFilled = false;
    OrderNode<Order> sellOrderNode = sellOrderHead;
    while (sellOrderNode != null && !isBuyFilled) {
      Order sellOrder = sellOrderNode.data;
      long sellOrderQty = sellOrder.remainingQty();
      long buyOrderQtyToFill = order.remainingQty();

      long qty = Math.min(sellOrderQty, buyOrderQtyToFill);
      isBuyFilled = order.applyFill(qty);
      boolean isSellFilled = sellOrder.applyFill(qty);

      if (isBuyFilled) {
        order.fill();
        isBuyFilled = true;
      }

      if (isSellFilled) {
        sellOrder.fill();
        removeOrder(sellOrderNode);
      }

      sellOrderNode = sellOrderNode.next;
    }

    return isBuyFilled;
  }

  /**
   * @param order the Sell Order to match against the BuySide Book
   * @return True if successfully matched. False otherwise
   */
  private static boolean matchSellOrder(final Order order) {
    long priceLevel = order.price();

    OrderNode<Order> buyOrderHead = buySideBook.get(priceLevel);
    if (buyOrderHead == null || buyOrderHead.data == null) {
      return false;
    }

    boolean isSellFilled = false;
    OrderNode<Order> buyOrderNode = buyOrderHead;
    while (buyOrderNode != null && !isSellFilled) {
      Order buyOrder = buyOrderNode.data;
      long buyOrderQty = buyOrder.remainingQty();
      long buyOrderQtyToFill = order.remainingQty();

      long qty = Math.min(buyOrderQty, buyOrderQtyToFill);
      isSellFilled = order.applyFill(qty);
      boolean isBuyFilled = buyOrder.applyFill(qty);

      if (isSellFilled) {
        order.fill();
        isSellFilled = true;
      }

      if (isBuyFilled) {
        buyOrder.fill();
        removeOrder(buyOrderNode);
      }

      buyOrderNode = buyOrderNode.next;
    }

    return isSellFilled;
  }

  /**
   * Removes the given OrderNode from the order book
   *
   * @param orderNode the OrderNode to remove from the book
   */
  private static void removeOrder(final OrderNode<Order> orderNode) {
    orderMap.remove(orderNode.data.uid());
    OrderNode<Order> prev = orderNode.prev;
    OrderNode<Order> next = orderNode.next;
    prev.next = next;
    next.prev = prev;
  }

  /**
   * @param order the Buy Order to rest on the Buy Side book
   * @return True if successfully rested. False otherwise
   */
  private static boolean restBuyOrder(final Order order) {
    if (buySideBook.containsKey(order.price())) {
      OrderNode<Order> tail = buySideBook.get(order.price()).tail;
      OrderNode<Order> prev = tail.prev;
      prev.next = new OrderNode<Order>(tail.head, tail, tail, prev, order);
      tail.prev = prev.next;
      return true;
    }

    OrderNode<Order> tail = new OrderNode<>();
    OrderNode<Order> head = new OrderNode<>();

    tail.head = head;
    head.head = head;

    tail.tail = tail;
    head.tail = tail;

    OrderNode<Order> orderNode = new OrderNode<Order>(tail.head, tail, tail, head, order);

    tail.prev = orderNode;
    head.next = orderNode;

    orderMap.put(order.uid(), orderNode);
    buySideBook.put(order.price(), head);

    return true;
  }

  /**
   * @param order the Sell Order to rest on the Sell Side book
   * @return True if successfully rested. False otherwise
   */
  private static boolean restSellOrder(final Order order) {
    if (sellSideBook.containsKey(order.price())) {
      OrderNode<Order> tail = sellSideBook.get(order.price()).tail;
      OrderNode<Order> prev = tail.prev;
      prev.next = new OrderNode<Order>(tail.head, tail, tail, prev, order);
      tail.prev = prev.next;
      return true;
    }

    OrderNode<Order> tail = new OrderNode<>();
    OrderNode<Order> head = new OrderNode<>();

    tail.head = head;
    head.head = head;

    tail.tail = tail;
    head.tail = tail;

    OrderNode<Order> orderNode = new OrderNode<Order>(tail.head, tail, tail, head, order);

    tail.prev = orderNode;
    head.next = orderNode;

    orderMap.put(order.uid(), orderNode);
    sellSideBook.put(order.price(), head);

    return true;
  }
}
