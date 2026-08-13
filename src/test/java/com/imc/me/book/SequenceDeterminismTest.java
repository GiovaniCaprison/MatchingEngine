package com.imc.me.book;

import static org.assertj.core.api.Assertions.assertThat;

import com.imc.me.domain.OrderSide;
import com.imc.me.domain.OrderType;
import com.imc.me.domain.Trade;
import com.imc.me.event.sink.CollectingTradeSink;
import com.imc.me.matching.Matcher;
import com.imc.me.matching.TradeSink;
import com.imc.me.sequencer.Sequencer;
import com.imc.me.support.Requirement;
import com.imc.me.support.TestTags;
import com.imc.me.util.Seq;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Every execution is stamped with its place in one total order, and replay reproduces it exactly. */
@Tag(TestTags.FAST)
@DisplayName("Book | Sequencing and determinism")
class SequenceDeterminismTest {

  /** Fills in fixed slices, so one submit produces several executions to be numbered. */
  private static final class SlicingMatcher implements Matcher {
    private final long slice;

    SlicingMatcher(final long slice) {
      this.slice = slice;
    }

    @Override
    public void match(final Order aggressor, final BookSide opposing, final TradeSink sink) {
      while (aggressor.remainingQty() > 0) {
        final long qty = Math.min(slice, aggressor.remainingQty());
        aggressor.applyFill(qty);
        sink.onTrade(aggressor.orderId(), 900L, aggressor.price(), qty);
      }
    }

    @Override
    public long fillableQty(final Order aggressor, final BookSide opposing) {
      return aggressor.remainingQty();
    }
  }

  private static Order limit(final long id, final long qty) {
    return Order.of(id, 100L, qty, OrderSide.BUY, OrderType.LIMIT);
  }

  @Test
  @Requirement("FR-3.4")
  @DisplayName("FR-3.4: executions are numbered monotonically with no gaps or repeats")
  void executions_are_numbered_in_one_stream() {
    final OrderBook book = new TreeMapOrderBook(new SlicingMatcher(3L));
    final CollectingTradeSink fills = new CollectingTradeSink();

    book.submit(limit(1L, 9L), fills);
    final Seq<Trade> trades = fills.fills();

    assertThat(trades.size()).isEqualTo(3);
    assertThat(trades.get(0).sequence()).isEqualTo(1L);
    assertThat(trades.get(1).sequence()).isEqualTo(2L);
    assertThat(trades.get(2).sequence()).isEqualTo(3L);
  }

  @Test
  @Requirement("NFR-1.1")
  @DisplayName("NFR-1.1: identical input produces identical trades, sequence numbers included")
  void replay_reproduces_trades_exactly() {
    final Seq<Trade> first = run();
    final Seq<Trade> second = run();

    // Value equality all the way down: Trade is a record, Seq compares element-wise. If sequence
    // numbers came from a clock or from several counters, this assertion could not exist.
    assertThat(first).isEqualTo(second);
  }

  private static Seq<Trade> run() {
    final OrderBook book = new TreeMapOrderBook(new SlicingMatcher(4L));
    final CollectingTradeSink fills = new CollectingTradeSink();
    book.submit(limit(1L, 10L), fills);
    book.submit(limit(2L, 6L), fills);
    return fills.fills();
  }

  @Test
  @Requirement("NFR-1.2")
  @DisplayName("NFR-1.2: two books sharing a sequencer share one total order")
  void a_shared_sequencer_gives_one_total_order() {
    final Sequencer shared = new Sequencer();
    final OrderBook first = new TreeMapOrderBook(new SlicingMatcher(5L), shared);
    final OrderBook second = new TreeMapOrderBook(new SlicingMatcher(5L), shared);
    final CollectingTradeSink fills = new CollectingTradeSink();

    first.submit(limit(1L, 5L), fills);
    second.submit(limit(2L, 5L), fills);
    first.submit(limit(3L, 5L), fills);

    // The point of one counter rather than several: "did A precede B" is an integer comparison
    // across the whole engine, not just within one book (OOD-13).
    assertThat(fills.fills().get(0).sequence()).isEqualTo(1L);
    assertThat(fills.fills().get(1).sequence()).isEqualTo(2L);
    assertThat(fills.fills().get(2).sequence()).isEqualTo(3L);
  }

  @Test
  @Requirement("NFR-1.1")
  @DisplayName("NFR-1.1: a sequencer never repeats a value and never issues zero")
  void sequencer_is_monotonic_and_skips_zero() {
    final Sequencer sequencer = new Sequencer();

    assertThat(sequencer.current()).isZero();
    assertThat(sequencer.next()).isEqualTo(1L);
    assertThat(sequencer.next()).isEqualTo(2L);
    assertThat(sequencer.current()).isEqualTo(2L);

    // Resuming from a known point is what makes replay continue rather than collide.
    assertThat(new Sequencer(500L).next()).isEqualTo(501L);
  }

  @Test
  @Requirement("NFR-1.1")
  @DisplayName("NFR-1.1: the stamping sink is reused without leaking between commands")
  void stamper_reuse_does_not_leak_between_commands() {
    final OrderBook book = new TreeMapOrderBook(new SlicingMatcher(10L));
    final CollectingTradeSink firstSink = new CollectingTradeSink();
    final CollectingTradeSink secondSink = new CollectingTradeSink();

    // The stamper is a single reused instance retargeted per command (OOD-11). If retargeting were
    // wrong, the second command's trades would arrive at the first command's consumer.
    book.submit(limit(1L, 10L), firstSink);
    book.submit(limit(2L, 10L), secondSink);

    assertThat(firstSink.count()).isEqualTo(1);
    assertThat(secondSink.count()).isEqualTo(1);
    assertThat(firstSink.fills().get(0).sequence()).isEqualTo(1L);
    assertThat(secondSink.fills().get(0).sequence()).isEqualTo(2L);
  }
}
