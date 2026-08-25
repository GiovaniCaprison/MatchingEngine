package io.github.giovanicaprison.matching.naive;

import io.github.giovanicaprison.matching.protocol.PricingInstruction;
import io.github.giovanicaprison.matching.protocol.Side;
import io.github.giovanicaprison.matching.protocol.TimeInForce;
import java.util.Comparator;

/**
 * One order, mutable, with every kind of order in the same shape (P-7).
 *
 * <p>No subclasses and no order type field. A stop is an order with a trigger price, an iceberg is
 * one whose displayed quantity is below its total, and the walk is identical for all of them. Five
 * classes behind one interface would put a megamorphic call site in the hottest loop in the system.
 *
 * <p>An object per command is this rung's whole point. The rungs above it stop allocating, and the
 * difference between them is the measurement.
 */
final class Order {

  /** Earliest first, which is every tie-break and every report order in the venue. */
  static final Comparator<Order> BY_ARRIVAL = Comparator.comparingLong(Order::arrival);

  private final long id;
  private final long clientOrderId;
  private final int participantId;
  private final Side side;
  private final PricingInstruction pricing;
  private final TimeInForce timeInForce;
  private final boolean postOnly;
  private final long minQuantity;
  private final long triggerPrice;
  private final long smpId;
  private final long displaySize;

  private long price;
  private long remaining;
  private long displayed;
  private long arrival;
  private long executed;

  Order(
      final long id,
      final long clientOrderId,
      final int participantId,
      final Side side,
      final PricingInstruction pricing,
      final TimeInForce timeInForce,
      final boolean postOnly,
      final long price,
      final long quantity,
      final long minQuantity,
      final long displayQuantity,
      final long triggerPrice,
      final long smpId,
      final long arrival) {
    this(
        id,
        clientOrderId,
        participantId,
        side,
        pricing,
        timeInForce,
        postOnly,
        price,
        quantity,
        minQuantity,
        displayQuantity,
        triggerPrice,
        smpId,
        arrival,
        0);
  }

  /** The same order under a new price or quantity, carrying what it has already executed. */
  Order(
      final long id,
      final long clientOrderId,
      final int participantId,
      final Side side,
      final PricingInstruction pricing,
      final TimeInForce timeInForce,
      final boolean postOnly,
      final long price,
      final long quantity,
      final long minQuantity,
      final long displayQuantity,
      final long triggerPrice,
      final long smpId,
      final long arrival,
      final long executed) {
    this.executed = executed;
    this.id = id;
    this.clientOrderId = clientOrderId;
    this.participantId = participantId;
    this.side = side;
    this.pricing = pricing;
    this.timeInForce = timeInForce;
    this.postOnly = postOnly;
    this.price = price;
    this.remaining = quantity;
    this.minQuantity = minQuantity;
    this.displaySize = displayQuantity;
    this.displayed = displayQuantity == 0 ? quantity : Math.min(displayQuantity, quantity);
    this.triggerPrice = triggerPrice;
    this.smpId = smpId;
    this.arrival = arrival;
  }

  long id() {
    return id;
  }

  long clientOrderId() {
    return clientOrderId;
  }

  int participantId() {
    return participantId;
  }

  Side side() {
    return side;
  }

  PricingInstruction pricing() {
    return pricing;
  }

  TimeInForce timeInForce() {
    return timeInForce;
  }

  boolean postOnly() {
    return postOnly;
  }

  long price() {
    return price;
  }

  long remaining() {
    return remaining;
  }

  /** What the feed has been told about, which is never the hidden part (FR-5.2). */
  long displayed() {
    return displayed;
  }

  long minQuantity() {
    return minQuantity;
  }

  long triggerPrice() {
    return triggerPrice;
  }

  long smpId() {
    return smpId;
  }

  long arrival() {
    return arrival;
  }

  /**
   * How much of this order has traded, over its whole life and across every replace.
   *
   * <p>A replace names the order's total quantity, so this is what the remainder is worked out from
   * (FR-4.9). It survives a replace because the order does.
   */
  long executed() {
    return executed;
  }

  /** The tranche size an iceberg shows at a time, which a replace has to preserve (FR-4.10). */
  long displaySize() {
    return displaySize;
  }

  /** Whether this order would trade at a candidate price: at it, or better from its own side. */
  boolean willingAt(final long candidate) {
    return side == Side.BUY ? price >= candidate : price <= candidate;
  }

  /** A stop rests in the trigger book and is not book liquidity (FR-6.1). */
  boolean stop() {
    return triggerPrice != 0;
  }

  boolean restsOnRemainder() {
    return pricing == PricingInstruction.LIMIT
        && (timeInForce == TimeInForce.GOOD_TILL_CANCEL || timeInForce == TimeInForce.DAY);
  }

  /**
   * Takes quantity from the displayed part first, since that is all a taker can see.
   *
   * <p>What an order shows only means anything while it is resting, and a resting order is never
   * asked for more than it shows. An order taking liquidity can take more than its tranche in one
   * step, and what it shows is worked out again from what is left if it goes on to rest, so there
   * is nothing to guard here.
   *
   * <p>Returns whether the displayed part is now empty while quantity remains, which is when a
   * further tranche is displayed and joins the back of its queue (FR-5.4).
   */
  boolean take(final long quantity) {
    remaining -= quantity;
    executed += quantity;
    displayed -= quantity;
    return displayed == 0 && remaining > 0;
  }

  /**
   * This order is joining the queue at its price: what it shows and where it stands are settled
   * now.
   *
   * <p>Now, rather than when the command arrived. An order that crossed on the way in spent its
   * tranche against whatever it took, and it queues behind anything that joined while it was
   * walking, including a tranche some other iceberg replenished on the way. Keeping the arrival it
   * had when it was created would put it in front of orders the feed already said were ahead of it.
   *
   * <p>The same operation serves a replenishment, because that is the same thing: a tranche joining
   * the back of the queue at its price.
   */
  void rest(final long arrivalSequence) {
    displayed = displaySize == 0 ? remaining : Math.min(displaySize, remaining);
    arrival = arrivalSequence;
  }

  /**
   * A replace that keeps queue position (FR-4.4) changes what is left and nothing else.
   *
   * @param remainder what should still be working, which the caller derives from the order's total
   */
  void reduceTo(final long remainder) {
    remaining = remainder;
    displayed = displaySize == 0 ? remainder : Math.min(displaySize, remainder);
  }

  /** A triggered stop becomes an ordinary order of its own pricing instruction (FR-6.3). */
  Order triggered(final long arrivalSequence) {
    return new Order(
        id,
        clientOrderId,
        participantId,
        side,
        pricing,
        timeInForce,
        postOnly,
        price,
        remaining,
        minQuantity,
        displaySize,
        0,
        smpId,
        arrivalSequence);
  }
}
