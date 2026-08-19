package com.imc.me.book;

import static org.assertj.core.api.Assertions.assertThat;

import com.imc.me.domain.OrderSide;
import com.imc.me.domain.OrderType;
import com.imc.me.event.result.AmendOutcome;
import com.imc.me.event.sink.TradeEventSink;
import com.imc.me.matching.Matcher;
import com.imc.me.matching.TradeSink;
import com.imc.me.support.Requirement;
import com.imc.me.support.TestTags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Amend semantics, and above all what each path does to <b>queue priority</b> (FR-4.3, FR-4.4,
 * FR-4.5).
 *
 * <p>Priority is asserted structurally — by walking the level's FIFO list to see which order is at the
 * front — because that is the only thing that actually determines who fills next. Asserting the
 * returned outcome alone would still pass if the list order were wrong.
 */
@Tag(TestTags.FAST)
@DisplayName("Book | Amend priority")
class AmendPriorityTest {

  /**
   * A matcher whose crossing behaviour is set per test, so amend behaviour is isolated from matching
   * behaviour. {@code fillable} drives the gate, {@code willFill} drives the walk.
   */
  private static final class ConfigurableMatcher implements Matcher {
    private long fillable;
    private long willFill;

    @Override
    public void match(final Order aggressor, final BookSide opposing, final TradeSink sink) {
      final long qty = Math.min(willFill, aggressor.remainingQty());
      if (qty > 0) {
        aggressor.applyFill(qty);
        sink.onTrade(aggressor.orderId(), 999L, aggressor.price(), qty);
      }
    }

    @Override
    public long fillableQty(final Order aggressor, final BookSide opposing) {
      return Math.min(fillable, aggressor.remainingQty());
    }
  }

  private static final TradeEventSink IGNORE = (s, a, r, p, q) -> {};

  private final ConfigurableMatcher matcher = new ConfigurableMatcher();
  private final TreeMapOrderBook book = new TreeMapOrderBook(matcher);

  private static Order limit(final long id, final long price, final long qty) {
    return Order.of(id, price, qty, OrderSide.BUY, OrderType.LIMIT);
  }

  /** The order at the front of the best bid level — the one that fills next. */
  private long frontOfBestBid() {
    return book.sideFor(OrderSide.BUY).bestLevel().first().orderId();
  }

  @Test
  @Requirement("FR-4.5")
  @DisplayName("FR-4.5: reducing quantity keeps time priority")
  void qty_decrease_keeps_priority() {
    final Order first = limit(1L, 100L, 10L);
    book.submit(first, IGNORE);
    book.submit(limit(2L, 100L, 10L), IGNORE);

    assertThat(book.amend(1L, 4L, 100L, IGNORE)).isEqualTo(AmendOutcome.REDUCED_KEPT_PRIORITY);

    // Reducing takes nothing from anyone queued behind, so there is no fairness argument for
    // re-queueing: order 1 is still at the front.
    assertThat(frontOfBestBid()).isEqualTo(1L);
    assertThat(book.topOfBook(OrderSide.BUY).qty()).isEqualTo(14L);
    assertThat(first.remainingQty()).isEqualTo(4L);
    assertThat(first.withdrawnQty()).isEqualTo(6L);
    // initialQty is untouched, so the audit trail still shows what the client originally asked for.
    assertThat(first.initialQty()).isEqualTo(10L);
  }

  @Test
  @Requirement("FR-4.4")
  @DisplayName("FR-4.4: increasing quantity loses time priority")
  void qty_increase_loses_priority() {
    book.submit(limit(1L, 100L, 10L), IGNORE);
    book.submit(limit(2L, 100L, 10L), IGNORE);

    assertThat(book.amend(1L, 25L, 100L, IGNORE)).isEqualTo(AmendOutcome.REQUEUED_LOST_PRIORITY);

    // Otherwise a client could hold a good queue position with a token order and inflate it on
    // seeing flow -- the abuse price-time priority exists to prevent.
    assertThat(frontOfBestBid()).isEqualTo(2L);
    assertThat(book.topOfBook(OrderSide.BUY).qty()).isEqualTo(35L);
  }

  @Test
  @Requirement("FR-4.4")
  @DisplayName("FR-4.4: repricing loses time priority and moves the order to a new level")
  void reprice_loses_priority_and_moves_level() {
    book.submit(limit(1L, 100L, 10L), IGNORE);
    book.submit(limit(2L, 100L, 10L), IGNORE);

    assertThat(book.amend(1L, 10L, 99L, IGNORE)).isEqualTo(AmendOutcome.REQUEUED_LOST_PRIORITY);

    // The old level keeps only order 2, and a new level exists behind it at 99.
    assertThat(book.topOfBook(OrderSide.BUY).price()).isEqualTo(100L);
    assertThat(book.topOfBook(OrderSide.BUY).qty()).isEqualTo(10L);
    assertThat(book.depth(OrderSide.BUY, 5).levels().size()).isEqualTo(2);
    assertThat(book.depth(OrderSide.BUY, 5).levels().get(1).price()).isEqualTo(99L);
    assertThat(book.sideFor(OrderSide.BUY).get(1L).price()).isEqualTo(99L);
  }

  @Test
  @Requirement("FR-4.3")
  @DisplayName("FR-4.3: a same-price same-qty amend re-queues rather than pretending")
  void no_op_amend_requeues() {
    book.submit(limit(1L, 100L, 10L), IGNORE);
    book.submit(limit(2L, 100L, 10L), IGNORE);

    // Only a strict DECREASE at the same price earns priority retention. Treating "no change" as a
    // decrease would hand out priority retention for a request that changed nothing -- a
    // queue-holding primitive dressed up as a no-op.
    assertThat(book.amend(1L, 10L, 100L, IGNORE)).isEqualTo(AmendOutcome.REQUEUED_LOST_PRIORITY);
    assertThat(frontOfBestBid()).isEqualTo(2L);
  }

  @Test
  @Requirement("API-3.1")
  @DisplayName("API-3.1: amending an unknown id is not-found, never an exception")
  void unknown_id_is_not_found() {
    assertThat(book.amend(404L, 10L, 100L, IGNORE)).isEqualTo(AmendOutcome.NOT_FOUND);
  }

  @Test
  @Requirement("API-3.1")
  @DisplayName("API-3.1: repricing across the spread executes instead of resting")
  void aggressive_reprice_executes() {
    book.submit(limit(1L, 100L, 10L), IGNORE);
    assertThat(book.topOfBook(OrderSide.BUY).qty()).isEqualTo(10L);

    // The reprice crosses, so the replacement fills on entry rather than joining a queue. An amend
    // is not a book-only operation, which is why it takes a sink.
    matcher.fillable = 10L;
    matcher.willFill = 10L;
    assertThat(book.amend(1L, 10L, 105L, IGNORE)).isEqualTo(AmendOutcome.FILLED_ON_AMEND);

    assertThat(book.topOfBook(OrderSide.BUY).isEmpty()).isTrue();
    assertThat(book.sideFor(OrderSide.BUY).get(1L)).isNull();
  }

  @Test
  @Requirement("API-3.1")
  @DisplayName("API-3.1: a partially filled reprice keeps its remainder resting")
  void partially_filled_reprice_rests_remainder() {
    book.submit(limit(1L, 100L, 10L), IGNORE);

    matcher.fillable = 4L;
    matcher.willFill = 4L;
    assertThat(book.amend(1L, 10L, 105L, IGNORE)).isEqualTo(AmendOutcome.REQUEUED_LOST_PRIORITY);

    assertThat(book.topOfBook(OrderSide.BUY).price()).isEqualTo(105L);
    assertThat(book.topOfBook(OrderSide.BUY).qty()).isEqualTo(6L);
  }

  @Test
  @Requirement("FR-2.6")
  @DisplayName("FR-2.6: a POST amend that would cross is refused, original left resting")
  void post_amend_that_would_cross_leaves_original_untouched() {
    final Order post = Order.of(1L, 100L, 10L, OrderSide.BUY, OrderType.POST);
    book.submit(post, IGNORE);
    assertThat(book.topOfBook(OrderSide.BUY).qty()).isEqualTo(10L);

    // Now the new price would take liquidity. The gate must fire BEFORE the original is unlinked:
    // silently cancelling an order the client asked to KEEP is the worst reading of "rejected".
    matcher.fillable = 5L;
    assertThat(book.amend(1L, 10L, 105L, IGNORE)).isEqualTo(AmendOutcome.REJECTED_WOULD_CROSS);

    assertThat(book.topOfBook(OrderSide.BUY).price()).isEqualTo(100L);
    assertThat(book.topOfBook(OrderSide.BUY).qty()).isEqualTo(10L);
    assertThat(post.remainingQty()).isEqualTo(10L);
    assertThat(frontOfBestBid()).isEqualTo(1L);
  }
}
