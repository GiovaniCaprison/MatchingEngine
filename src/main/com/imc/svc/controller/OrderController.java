package svc.controller;

import svc.core.OrderBookService;
import svc.core.OrderIdentifierService;
import svc.model.Order;
import svc.model.OrderSide;
import svc.model.OrderType;

public class OrderController {
  // For now we only support Market and Limit orders
  // externalId is equal to internalId right now - this is not always going to be the case
  public static long placeOrder(
      final long originalQty, final long price, final OrderSide side, final OrderType type) {
    Order order = resolveOrder(originalQty, price, side, type);
    OrderBookService.placeOrder(order);
    return OrderIdentifierService.externalId(order.uid());
  }

  private static Order resolveOrder(
      final long originalQty, final long price, final OrderSide side, final OrderType type) {
    if (side == OrderSide.BUY) {
      switch (type) {
        case OrderType.MARKET:
          return Order.marketBuy(originalQty, OrderIdentifierService.internalId());
        case OrderType.LIMIT:
          return Order.limitBuy(price, originalQty, OrderIdentifierService.internalId());
        default:
          throw new IllegalArgumentException(
              "Only Market and Limit order types are currently supported");
      }
    } else {
      switch (type) {
        case OrderType.MARKET:
          return Order.marketSell(originalQty, OrderIdentifierService.internalId());
        case OrderType.LIMIT:
          return Order.limitSell(price, originalQty, OrderIdentifierService.internalId());
        default:
          throw new IllegalArgumentException(
              "Only Market and Limit order types are currently supported");
      }
    }
  }
}
