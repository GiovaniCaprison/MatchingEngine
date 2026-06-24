package svc.model;

import svc.util.Validator;

/** The Order entity */
public class Order {
  // Constant price of Market Orders.
  // Maintains the predicate:
  // boolean crosses = bidPrice >= askPrice;
  private static final long MARKET_ORDER_BUY_PRICE = Long.MAX_VALUE;
  private static final long MARKET_ORDER_SELL_PRICE = Long.MIN_VALUE;

  // Immutable attributes of the Order entity.
  private final OrderSide orderSide;
  private final OrderType orderType;
  private final long uid;
  private final long price;
  private final long originalQty;

  // Mutable atributes of the Order entity.
  private OrderStatus orderStatus;
  private long filledQty;

  /**
   * Constructor for the Order entity.
   *
   * @param orderSide the side of the Order
   * @param orderType the type of the Order
   * @param price the price at which the Order is being made
   * @param originalQty the initial quantity of the Order
   * @param uid the unique identifier of the Order
   * @return The order object
   */
  private Order(
      final OrderSide orderSide,
      final OrderType orderType,
      final long price,
      final long originalQty,
      final long uid) {
    this.orderSide = orderSide;
    this.orderType = orderType;
    this.uid = uid;
    this.price = price;
    this.originalQty = originalQty;

    this.orderStatus = OrderStatus.OPEN;
    this.filledQty = 0;
  }

  /**
   * Static factory method used to create a new Limit Buy Order
   *
   * @param price the price at which the order is being made
   * @param originalQty the initial quantity of the Order
   * @param uid the unique identifier of the Order
   * @return A Limit Buy Order object
   */
  public static Order limitBuy(final long price, final long originalQty, final long uid) {
    return new Order(
        OrderSide.BUY,
        OrderType.LIMIT,
        Validator.requirePositive(price, "price"),
        Validator.requirePositive(originalQty, "originalQty"),
        uid);
  }

  /**
   * Static factory method used to create a new Limit Sell Order
   *
   * @param price the price at which the order is being made
   * @param originalQty the initial quantity of the Order
   * @param uid the unique identifier of the Order
   * @return A Limit Sell Order object
   */
  public static Order limitSell(final long price, final long originalQty, final long uid) {
    return new Order(
        OrderSide.SELL,
        OrderType.LIMIT,
        Validator.requirePositive(price, "price"),
        Validator.requirePositive(originalQty, "originalQty"),
        uid);
  }

  /**
   * Static factory method used to create a new Market Buy Order
   *
   * @param originalQty the initial quantity of the Order
   * @param uid the unique identifier of the Order
   * @return A Market Buy Order object
   */
  public static Order marketBuy(final long originalQty, final long uid) {
    return new Order(
        OrderSide.BUY,
        OrderType.MARKET,
        MARKET_ORDER_BUY_PRICE,
        Validator.requirePositive(originalQty, "originalQty"),
        uid);
  }

  /**
   * Static factory method used to create a new Market Sell Order
   *
   * @param originalQty the initial quantity of the Order
   * @param uid the unique identifier of the Order
   * @return A Market Sell Order object
   */
  public static Order marketSell(final long originalQty, final long uid) {
    return new Order(
        OrderSide.SELL,
        OrderType.MARKET,
        MARKET_ORDER_SELL_PRICE,
        Validator.requirePositive(originalQty, "originalQty"),
        uid);
  }

  /**
   * Static factory method used to create a new IOC Buy Order
   *
   * @param price the price at which the order is being made
   * @param originalQty the initial quantity of the Order
   * @param uid the unique identifier of the Order
   * @return An IOC Buy Order object
   */
  public static Order iocBuy(final long price, final long originalQty, final long uid) {
    return new Order(
        OrderSide.BUY,
        OrderType.IOC,
        Validator.requirePositive(price, "price"),
        Validator.requirePositive(originalQty, "originalQty"),
        uid);
  }

  /**
   * Static factory method used to create a new IOC Sell Order
   *
   * @param price the price at which the order is being made
   * @param originalQty the initial quantity of the Order
   * @param uid the unique identifier of the Order
   * @return An IOC Sell Order object
   */
  public static Order iocSell(final long price, final long originalQty, final long uid) {
    return new Order(
        OrderSide.SELL,
        OrderType.IOC,
        Validator.requirePositive(price, "price"),
        Validator.requirePositive(originalQty, "originalQty"),
        uid);
  }

  /**
   * Static factory method used to create a new FOK Buy Order
   *
   * @param price the price at which the order is being made
   * @param originalQty the initial quantity of the Order
   * @param uid the unique identifier of the Order
   * @return A FOK Buy Order object
   */
  public static Order fokBuy(final long price, final long originalQty, final long uid) {
    return new Order(
        OrderSide.BUY,
        OrderType.FOK,
        Validator.requirePositive(price, "price"),
        Validator.requirePositive(originalQty, "originalQty"),
        uid);
  }

  /**
   * Static factory method used to create a new FOK Sell Order
   *
   * @param price the price at which the order is being made
   * @param originalQty the initial quantity of the Order
   * @param uid the unique identifier of the Order
   * @return A FOK Sell Order object
   */
  public static Order fokSell(final long price, final long originalQty, final long uid) {
    return new Order(
        OrderSide.SELL,
        OrderType.FOK,
        Validator.requirePositive(price, "price"),
        Validator.requirePositive(originalQty, "originalQty"),
        uid);
  }

  /**
   * Static factory method used to create a new Post Buy Order
   *
   * @param price the price at which the order is being made
   * @param originalQty the initial quantity of the Order
   * @param uid the unique identifier of the Order
   * @return A Post Buy Order object
   */
  public static Order postBuy(final long price, final long originalQty, final long uid) {
    return new Order(
        OrderSide.BUY,
        OrderType.POST,
        Validator.requirePositive(price, "price"),
        Validator.requirePositive(originalQty, "originalQty"),
        uid);
  }

  /**
   * Static factory method used to create a new Post Sell Order
   *
   * @param price the price at which the order is being made
   * @param originalQty the initial quantity of the Order
   * @param uid the unique identifier of the Order
   * @return A Post Sell Order object
   */
  public static Order postSell(final long price, final long originalQty, final long uid) {
    return new Order(
        OrderSide.SELL,
        OrderType.POST,
        Validator.requirePositive(price, "price"),
        Validator.requirePositive(originalQty, "originalQty"),
        uid);
  }

  /**
   * Gets the remaining quantity of the Order yet to be filled
   *
   * @return The remaining quantity
   */
  public long remainingQty() {
    return this.originalQty - this.filledQty;
  }

  /**
   * Gets the OrderStatus of the Order
   *
   * @return OrderStatus of the Order
   */
  public OrderStatus orderStatus() {
    return this.orderStatus;
  }

  /**
   * Gets the OrderSide of the Order
   *
   * @return OrderSide of the Order
   */
  public OrderSide orderSide() {
    return this.orderSide;
  }

  /**
   * Gets the OrderType of the Order
   *
   * @return OrderType of the Order
   */
  public OrderType orderType() {
    return this.orderType;
  }

  /**
   * Gets the unique identifier of the Order
   *
   * @return uid of the Order
   */
  public long uid() {
    return this.uid;
  }

  /**
   * Gets the price of the Order
   *
   * @return price of the Order
   */
  public long price() {
    return this.price;
  }

  /**
   * Gets the original quantity of the Order
   *
   * @return originalQty of the Order
   */
  public long originalQty() {
    return this.originalQty;
  }

  /** Sets the Order status to filled */
  public void fill() {
    this.orderStatus = OrderStatus.FILLED;
  }

  /** Sets the Order status to cancelled */
  public void cancel() {
    this.orderStatus = OrderStatus.CANCELLED;
  }

  /**
   * Applies a filled quantity of and to the Order
   *
   * @param qty the filled quantity which is applied to the Order
   * @return True if the order has been filled entirely after this operation. False otherwise
   */
  public boolean applyFill(final long qty) {
    this.filledQty += qty;
    return this.filledQty == this.originalQty;
  }
}
