package io.github.giovanicaprison.matching.naive;

import io.github.giovanicaprison.matching.protocol.Side;

/**
 * A resting order, as an ordinary mutable object.
 *
 * <p>One of these is allocated for every order that reaches the book, and that is the point of this
 * implementation rather than an oversight. Copying a command into an object is what a normal Java
 * engine does, and what it costs is one of the things this project measures (P-10).
 */
final class Order {

  final long orderId;
  final long clientOrderId;
  final long participantId;
  final Side side;
  final long price;
  long remainingQuantity;

  Order(
      final long orderId,
      final long clientOrderId,
      final long participantId,
      final Side side,
      final long price,
      final long quantity) {
    this.orderId = orderId;
    this.clientOrderId = clientOrderId;
    this.participantId = participantId;
    this.side = side;
    this.price = price;
    this.remainingQuantity = quantity;
  }
}
