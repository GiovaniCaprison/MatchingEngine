package io.github.giovanicaprison.matching.benchmarks;

import io.github.giovanicaprison.matching.protocol.CancelOrderEncoder;
import io.github.giovanicaprison.matching.protocol.MessageHeaderEncoder;
import io.github.giovanicaprison.matching.protocol.NewOrderEncoder;
import io.github.giovanicaprison.matching.protocol.PricingInstruction;
import io.github.giovanicaprison.matching.protocol.Side;
import io.github.giovanicaprison.matching.protocol.TimeInForce;
import java.util.Random;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Builds pre-encoded command logs.
 *
 * <p>Seeded, so a run is reproducible and a benchmark repeatable. Reproducibility here is a Java
 * guarantee, since {@link Random} has a specified algorithm; it does not extend to another
 * language, which is why a log rather than a seed is the thing you would hand a C++ process.
 *
 * <p>Flow is not endogenous. Orders do not react to the book, which is the right trade when the
 * point is to control the input rather than to model a market.
 */
public final class LogGenerator {

  private static final int MAX_MESSAGE_BYTES = 64;

  private final FlowParameters params;

  public LogGenerator(final FlowParameters params) {
    this.params = params;
  }

  /**
   * Orders that rest and never cross, alternating sides, used to warm a book to a given size.
   *
   * <p>Passive on both sides of a half spread, so nothing in a warm-up trades and the resulting
   * book size is exactly the command count.
   */
  public CommandLog passiveOrders(final int count, final long firstSequence) {
    final Builder log = new Builder(count);
    final Random random = new Random(params.seed());
    for (int i = 0; i < count; i++) {
      final Side side = (i % 2 == 0) ? Side.BUY : Side.SELL;
      log.newOrder(
          firstSequence + i,
          side,
          PricingInstruction.LIMIT,
          TimeInForce.GOOD_TILL_CANCEL,
          passivePrice(random, side),
          quantity(random));
    }
    return log.build();
  }

  /**
   * Orders priced to cross, each small enough to consume at most one resting order, so a batch of
   * them shrinks the book by a known amount.
   */
  public CommandLog crossingOrders(final int count, final long firstSequence) {
    final Builder log = new Builder(count);
    final Random random = new Random(params.seed() + 1);
    for (int i = 0; i < count; i++) {
      final Side side = (i % 2 == 0) ? Side.BUY : Side.SELL;
      log.newOrder(
          firstSequence + i,
          side,
          PricingInstruction.LIMIT,
          TimeInForce.IMMEDIATE_OR_CANCEL,
          aggressivePrice(random, side),
          params.minQuantity());
    }
    return log.build();
  }

  /** Cancels of order ids 1 upward, which is what a warm-up assigns in order. */
  public CommandLog cancels(final int count, final long firstOrderId, final long firstSequence) {
    final Builder log = new Builder(count);
    for (int i = 0; i < count; i++) {
      log.cancel(firstSequence + i, firstOrderId + i);
    }
    return log.build();
  }

  /** Away from the mid, so it rests. */
  private long passivePrice(final Random random, final Side side) {
    final long half = (long) params.halfSpreadTicks() * params.tick();
    final long out = ticksOut(random) * params.tick();
    return side == Side.BUY ? params.midPrice() - half - out : params.midPrice() + half + out;
  }

  /** Across the mid, so it crosses. */
  private long aggressivePrice(final Random random, final Side side) {
    final long half = (long) params.halfSpreadTicks() * params.tick();
    final long out = ticksOut(random) * params.tick();
    return side == Side.BUY ? params.midPrice() + half + out : params.midPrice() - half - out;
  }

  private long ticksOut(final Random random) {
    int out = 0;
    while (out < params.maxTicksFromTouch() && random.nextDouble() > params.placementDecay()) {
      out++;
    }
    return out;
  }

  private long quantity(final Random random) {
    final long span = params.maxQuantity() - params.minQuantity() + 1;
    return params.minQuantity() + random.nextInt((int) span);
  }

  /** Accumulates encoded messages back to back and records where each one starts. */
  private static final class Builder {

    private final UnsafeBuffer buffer;
    private final int[] offsets;
    private final int[] lengths;
    private final MessageHeaderEncoder header = new MessageHeaderEncoder();
    private final NewOrderEncoder newOrder = new NewOrderEncoder();
    private final CancelOrderEncoder cancelOrder = new CancelOrderEncoder();
    private int position;
    private int count;

    private Builder(final int capacity) {
      this.buffer = new UnsafeBuffer(new byte[capacity * MAX_MESSAGE_BYTES]);
      this.offsets = new int[capacity];
      this.lengths = new int[capacity];
    }

    private void newOrder(
        final long sequence,
        final Side side,
        final PricingInstruction pricing,
        final TimeInForce timeInForce,
        final long price,
        final long quantity) {
      newOrder.wrapAndApplyHeader(buffer, position, header);
      newOrder.frame().instrumentId(1).sequence(sequence);
      newOrder
          .clientOrderId(sequence)
          .participantId(1)
          .side(side)
          .pricing(pricing)
          .timeInForce(timeInForce)
          .price(price)
          .quantity(quantity);
      newOrder.flags().postOnly(false);
      record(header.encodedLength() + newOrder.encodedLength());
    }

    private void cancel(final long sequence, final long orderId) {
      cancelOrder.wrapAndApplyHeader(buffer, position, header);
      cancelOrder.frame().instrumentId(1).sequence(sequence);
      cancelOrder.clientOrderId(sequence).participantId(1).orderId(orderId);
      record(header.encodedLength() + cancelOrder.encodedLength());
    }

    private void record(final int length) {
      offsets[count] = position;
      lengths[count] = length;
      position += length;
      count++;
    }

    private CommandLog build() {
      return new CommandLog(buffer, offsets, lengths, count);
    }
  }
}
