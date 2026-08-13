package com.imc.me.book;

import static org.assertj.core.api.Assertions.assertThat;

import com.imc.me.domain.OrderSide;
import com.imc.me.domain.OrderType;
import com.imc.me.event.dto.Status;
import com.imc.me.event.sink.TradeEventSink;
import com.imc.me.matching.Matcher;
import com.imc.me.matching.TradeSink;
import com.imc.me.registry.OrderRegistry;
import com.imc.me.support.Requirement;
import com.imc.me.support.TestTags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Order status across the whole lifecycle, including after the order has left the book (FR-5.4).
 *
 * <p>In {@code com.imc.me.book} because driving an order to a partially-filled state means filling it,
 * which only this package can do (OOD-1).
 */
@Tag(TestTags.FAST)
@DisplayName("Book | Order registry")
class OrderRegistryTest {

  private static final TradeEventSink IGNORE = (s, a, r, p, q) -> {};

  /** Fills a fixed quantity, so a test can produce a partial fill. */
  private static final class PartialMatcher implements Matcher {
    private long willFill;

    @Override
    public void match(final Order aggressor, final BookSide opposing, final TradeSink sink) {
      final long qty = Math.min(willFill, aggressor.remainingQty());
      if (qty > 0) {
        aggressor.applyFill(qty);
        sink.onTrade(aggressor.orderId(), 900L, aggressor.price(), qty);
      }
    }

    @Override
    public long fillableQty(final Order aggressor, final BookSide opposing) {
      return Math.min(willFill, aggressor.remainingQty());
    }
  }

  private final OrderRegistry registry = new OrderRegistry();
  private final PartialMatcher matcher = new PartialMatcher();
  private final TreeMapOrderBook book = new TreeMapOrderBook(matcher);

  private Order accept(final long id, final long qty) {
    final Order order = Order.of(id, 100L, qty, OrderSide.BUY, OrderType.LIMIT);
    registry.accepted(order);
    return order;
  }

  @Test
  @Requirement("FR-5.4")
  @DisplayName("FR-5.4: an accepted, unfilled order is open with its full quantity remaining")
  void accepted_order_is_open() {
    accept(1L, 10L);

    assertThat(registry.statusOf(1L).status()).isEqualTo(Status.OPEN);
    assertThat(registry.statusOf(1L).remainingQty()).isEqualTo(10L);
    assertThat(registry.statusOf(1L).filledQty()).isZero();
  }

  @Test
  @Requirement("FR-5.4")
  @DisplayName("FR-5.4: a partially filled order reports both quantities")
  void partially_filled_order_reports_quantities() {
    final Order order = accept(1L, 10L);
    matcher.willFill = 4L;
    book.submit(order, IGNORE);

    // Derived from the live order rather than pushed in, so the registry cannot fall out of step
    // with the book (OOD-14).
    assertThat(registry.statusOf(1L).status()).isEqualTo(Status.PARTIALLY_FILLED);
    assertThat(registry.statusOf(1L).remainingQty()).isEqualTo(6L);
    assertThat(registry.statusOf(1L).filledQty()).isEqualTo(4L);
  }

  @Test
  @Requirement("FR-5.4")
  @DisplayName("FR-5.4: a filled order is still answerable after leaving the book")
  void filled_order_survives_leaving_the_book() {
    final Order order = accept(1L, 10L);
    matcher.willFill = 10L;
    book.submit(order, IGNORE);

    // This is the whole reason the registry exists: the order is gone from bids/asks, so the book
    // alone could not answer this.
    assertThat(book.sideFor(OrderSide.BUY).get(1L)).isNull();
    assertThat(registry.statusOf(1L).status()).isEqualTo(Status.FILLED);
    assertThat(registry.statusOf(1L).remainingQty()).isZero();
    assertThat(registry.statusOf(1L).filledQty()).isEqualTo(10L);
  }

  @Test
  @Requirement("FR-5.4")
  @DisplayName("FR-5.4: a cancelled order keeps the quantities it had when cancelled")
  void cancelled_order_keeps_its_quantities() {
    final Order order = accept(1L, 10L);
    matcher.willFill = 4L;
    book.submit(order, IGNORE);

    book.cancel(1L);
    registry.cancelled(1L);

    // Nothing about the quantities distinguishes "cancelled with 6 left" from "working with 6 left",
    // which is exactly why CANCELLED is recorded while OPEN/PARTIAL/FILLED are derived.
    assertThat(registry.statusOf(1L).status()).isEqualTo(Status.CANCELLED);
    assertThat(registry.statusOf(1L).remainingQty()).isEqualTo(6L);
    assertThat(registry.statusOf(1L).filledQty()).isEqualTo(4L);
  }

  @Test
  @Requirement("FR-5.4")
  @DisplayName("FR-5.4: a rejected order is registered rather than forgotten")
  void rejected_order_is_registered() {
    final Order order = Order.of(9L, 100L, 10L, OrderSide.BUY, OrderType.LIMIT);
    registry.rejected(order);

    // Otherwise a client whose order was rejected and whose query returns "unknown" cannot tell a
    // rejection from a lost message.
    assertThat(registry.statusOf(9L).status()).isEqualTo(Status.REJECTED);
  }

  @Test
  @Requirement("FR-5.4")
  @DisplayName("FR-5.4: an id the engine has never seen is null, not a lifecycle state")
  void unknown_id_is_null() {
    // null rather than a NOT_FOUND constant so that "never existed" cannot be mistaken for a real
    // state; the caller turns it into a typed outcome.
    assertThat(registry.statusOf(404L)).isNull();
    assertThat(registry.size()).isZero();
  }

  @Test
  @Requirement("FR-5.4")
  @DisplayName("FR-5.4: a terminal state is not undone by later quantity changes")
  void terminal_state_wins_over_derivation() {
    final Order order = accept(1L, 10L);
    book.submit(order, IGNORE);
    registry.cancelled(1L);

    matcher.willFill = 10L;
    book.submit(order, IGNORE);

    // The derivation would now say FILLED. A recorded terminal state outranks it, because the order
    // was withdrawn and anything that happened after is a bug elsewhere -- not a status change.
    assertThat(registry.statusOf(1L).status()).isEqualTo(Status.CANCELLED);
  }
}
