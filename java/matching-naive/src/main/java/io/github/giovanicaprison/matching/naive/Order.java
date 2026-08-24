package io.github.giovanicaprison.matching.naive;

import io.github.giovanicaprison.matching.protocol.PricingInstruction;
import io.github.giovanicaprison.matching.protocol.Side;
import io.github.giovanicaprison.matching.protocol.TimeInForce;

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

  boolean iceberg() {
    return displaySize > 0;
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
   * <p>Returns whether the displayed part is now empty while quantity remains, which is when a
   * further tranche is displayed and joins the back of its queue (FR-5.4).
   */
  boolean take(final long quantity) {
    remaining -= quantity;
    displayed -= quantity;
    return displayed == 0 && remaining > 0;
  }

  /** The next tranche, which is the smaller of the display size and what is left. */
  void replenish(final long arrivalSequence) {
    displayed = Math.min(displaySize, remaining);
    arrival = arrivalSequence;
  }

  /** A replace that keeps queue position (FR-4.4) changes quantity and nothing else. */
  void reduceTo(final long quantity) {
    remaining = quantity;
    displayed = displaySize == 0 ? quantity : Math.min(displaySize, quantity);
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
