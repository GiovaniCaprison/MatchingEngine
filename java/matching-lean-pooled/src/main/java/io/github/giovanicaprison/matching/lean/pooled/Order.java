package io.github.giovanicaprison.matching.lean.pooled;

import io.github.giovanicaprison.matching.protocol.PricingInstruction;
import io.github.giovanicaprison.matching.protocol.Side;
import io.github.giovanicaprison.matching.protocol.TimeInForce;

/**
 * One order with nothing on it a limit or market order does not need, mutable and reusable.
 *
 * <p>This is the object the comparison is about. The full engine's order carries a trigger price, a
 * display size, a minimum quantity, a self match id and a flag, and every one of them occupies the
 * layout whether or not the flow uses it. Here they do not exist, which is the only honest way to
 * measure what their existing costs (P-16). And like the rung it shadows, nothing here is final: an
 * order leaving the venue goes back to the pool and the next command wears the same object
 * (NFR-4.3).
 */
final class Order {

  static final java.util.Comparator<Order> BY_ARRIVAL =
      java.util.Comparator.comparingLong(Order::arrival);

  private long id;
  private long clientOrderId;
  private int participantId;
  private Side side;
  private PricingInstruction pricing;
  private TimeInForce timeInForce;

  private long price;
  private long remaining;
  private long arrival;
  private long executed;

  /**
   * The order's own place in whichever chain holds it: its level's queue or the pool's free list,
   * never both (P-13). This twin's book and pool own them.
   */
  Order previous;

  Order next;

  Order() {}

  /** A fresh life for a pooled object: every field is written, nothing survives the last one. */
  void init(
      final long id,
      final long clientOrderId,
      final int participantId,
      final Side side,
      final PricingInstruction pricing,
      final TimeInForce timeInForce,
      final long price,
      final long quantity,
      final long arrival,
      final long executed) {
    this.id = id;
    this.clientOrderId = clientOrderId;
    this.participantId = participantId;
    this.side = side;
    this.pricing = pricing;
    this.timeInForce = timeInForce;
    this.price = price;
    this.remaining = quantity;
    this.arrival = arrival;
    this.executed = executed;
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

  long price() {
    return price;
  }

  /** What is left is what is shown. Without icebergs the two are the same number. */
  long remaining() {
    return remaining;
  }

  long arrival() {
    return arrival;
  }

  /** What has traded across the order's whole life, which a replace works its remainder from. */
  long executed() {
    return executed;
  }

  boolean restsOnRemainder() {
    return pricing == PricingInstruction.LIMIT
        && (timeInForce == TimeInForce.GOOD_TILL_CANCEL || timeInForce == TimeInForce.DAY);
  }

  void take(final long quantity) {
    remaining -= quantity;
    executed += quantity;
  }

  void rest(final long arrivalSequence) {
    arrival = arrivalSequence;
  }

  void reduceTo(final long remainder) {
    remaining = remainder;
  }
}
