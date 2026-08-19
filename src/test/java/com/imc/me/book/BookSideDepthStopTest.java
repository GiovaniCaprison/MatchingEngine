package com.imc.me.book;

import static org.assertj.core.api.Assertions.assertThat;

import com.imc.me.domain.OrderSide;
import com.imc.me.domain.OrderType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The depth walk stops when the sink says so.
 *
 * <p>In this package rather than in {@code boundary} because the contract is between a side and its
 * sink, and nothing at the public API can see how many levels were visited.
 * {@code MatchingEngine.depth} always hands the side a collecting sink that wants every level.
 */
class BookSideDepthStopTest {

  /** Counts the levels it is offered, and stops after a given number. */
  private static final class CountingSink implements DepthSink {
    private final int stopAfter;
    private int seen;

    private CountingSink(final int stopAfter) {
      this.stopAfter = stopAfter;
    }

    @Override
    public boolean onLevel(final long price, final long qty) {
      seen++;
      return seen < stopAfter;
    }
  }

  private static BookSide sideWithLevels(final int count) {
    final BookSide side = new TreeMapBookSide(OrderSide.SELL);
    for (int i = 0; i < count; i++) {
      side.addOrder(Order.of(i + 1, 100L + i, 5L, OrderSide.SELL, OrderType.LIMIT));
    }
    return side;
  }

  @Test
  @DisplayName("a sink that returns false ends the walk")
  void sink_can_stop_the_walk() {
    final CountingSink sink = new CountingSink(3);
    sideWithLevels(50).depth(Integer.MAX_VALUE, sink);
    assertThat(sink.seen).isEqualTo(3);
  }

  @Test
  @DisplayName("the caller's bound still applies when the sink never stops")
  void bound_still_applies() {
    final CountingSink sink = new CountingSink(Integer.MAX_VALUE);
    sideWithLevels(50).depth(4, sink);
    assertThat(sink.seen).isEqualTo(4);
  }

  @Test
  @DisplayName("levels are offered best price first")
  void best_price_first() {
    final BookSide side = sideWithLevels(3);
    final long[] first = {0L};
    side.depth(
        Integer.MAX_VALUE,
        (price, qty) -> {
          if (first[0] == 0L) first[0] = price;
          return true;
        });
    assertThat(first[0]).isEqualTo(100L);
  }
}
