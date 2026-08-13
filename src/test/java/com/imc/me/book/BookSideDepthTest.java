package com.imc.me.book;

import static org.assertj.core.api.Assertions.assertThat;

import com.imc.me.domain.OrderSide;
import com.imc.me.domain.OrderType;
import com.imc.me.support.Requirement;
import com.imc.me.support.TestTags;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Depth is emitted in priority order and capped by the caller (FR-5.3, OOD-10).
 *
 * <p>In {@code com.imc.me.book} because building a populated side means constructing orders, and the
 * entity's mutators are package-private (OOD-1).
 */
@Tag(TestTags.FAST)
@DisplayName("Book | Bounded depth")
class BookSideDepthTest {

  /** A recording sink. Real consumers publish; this one remembers, so a test can assert. */
  private static final class RecordingDepthSink implements DepthSink {
    private final List<String> levels = new ArrayList<>();

    @Override
    public void onLevel(final long price, final long qty) {
      levels.add(price + "x" + qty);
    }
  }

  private static BookSide sideWith(final OrderSide side, final long... prices) {
    final BookSide book = new TreeMapBookSide(side);
    long id = 1L;
    for (final long price : prices) {
      book.addOrder(Order.of(id++, price, 10L, side, OrderType.LIMIT));
    }
    return book;
  }

  @Test
  @Requirement("FR-5.3")
  @DisplayName("FR-5.3: depth aggregates orders resting at the same price")
  void depth_aggregates_per_level() {
    final BookSide bids = new TreeMapBookSide(OrderSide.BUY);
    bids.addOrder(Order.of(1L, 100L, 10L, OrderSide.BUY, OrderType.LIMIT));
    bids.addOrder(Order.of(2L, 100L, 7L, OrderSide.BUY, OrderType.LIMIT));
    bids.addOrder(Order.of(3L, 99L, 5L, OrderSide.BUY, OrderType.LIMIT));

    final RecordingDepthSink sink = new RecordingDepthSink();
    bids.depth(10, sink);

    assertThat(sink.levels).containsExactly("100x17", "99x5");
  }

  @Test
  @Requirement("FR-5.3")
  @DisplayName("FR-5.3: bids descend from the best bid, asks ascend from the best ask")
  void depth_is_emitted_in_priority_order() {
    final RecordingDepthSink bidSink = new RecordingDepthSink();
    sideWith(OrderSide.BUY, 99L, 101L, 100L).depth(10, bidSink);
    assertThat(bidSink.levels).containsExactly("101x10", "100x10", "99x10");

    final RecordingDepthSink askSink = new RecordingDepthSink();
    sideWith(OrderSide.SELL, 101L, 99L, 100L).depth(10, askSink);
    assertThat(askSink.levels).containsExactly("99x10", "100x10", "101x10");
  }

  @Test
  @Requirement("FR-5.3")
  @DisplayName("FR-5.3: depth emits no more levels than the caller asked for")
  void depth_respects_the_cap() {
    final BookSide bids = sideWith(OrderSide.BUY, 100L, 99L, 98L, 97L, 96L);

    final RecordingDepthSink capped = new RecordingDepthSink();
    bids.depth(2, capped);
    assertThat(capped.levels).containsExactly("100x10", "99x10");

    // The cap truncates, it does not fail: asking for more than exists yields what exists.
    final RecordingDepthSink oversized = new RecordingDepthSink();
    bids.depth(50, oversized);
    assertThat(oversized.levels).hasSize(5);
  }

  @Test
  @Requirement("FR-5.2")
  @DisplayName("FR-5.2: an empty side emits nothing rather than a placeholder")
  void empty_side_emits_nothing() {
    final RecordingDepthSink sink = new RecordingDepthSink();
    new TreeMapBookSide(OrderSide.BUY).depth(5, sink);

    assertThat(sink.levels).isEmpty();
  }
}
