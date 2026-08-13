package com.imc.me.book;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imc.me.domain.OrderSide;
import com.imc.me.domain.OrderType;
import com.imc.me.event.result.SubmitOutcome;
import com.imc.me.matching.Matcher;
import com.imc.me.matching.TradeSink;
import com.imc.me.support.Requirement;
import com.imc.me.support.TestTags;
import com.imc.me.util.Prices;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The per-type gate and remainder policies (FR-2.1 .. FR-2.6).
 *
 * <p>Driven by a stub matcher rather than the real one, which is the point of the three-phase split
 * (OOD-8): because phases 1 and 3 are the only type-aware code and the walk is type-agnostic, all
 * five order types' policies are testable with the matching algorithm stubbed out. If the types had
 * been subclasses or per-type matchers, none of this would be reachable without the full walk.
 *
 * <p>Lives in {@code com.imc.me.book} because the stub has to fill an order, and the entity's
 * mutators are package-private (OOD-1).
 */
@Tag(TestTags.FAST)
@DisplayName("Book | Order type policy")
class OrderTypePolicyTest {

  /**
   * A matcher that fills a configured quantity and reports a configured fillable quantity.
   *
   * <p>Deliberately allows the two to disagree, so the FOK invariant can be tested: a real matcher
   * must never do that, and the book must fail loudly rather than silently if it does.
   */
  private static final class StubMatcher implements Matcher {
    private final long fillable;
    private final long willFill;
    private boolean walked;

    StubMatcher(final long fillable, final long willFill) {
      this.fillable = fillable;
      this.willFill = willFill;
    }

    @Override
    public void match(final Order aggressor, final BookSide opposing, final TradeSink sink) {
      walked = true;
      if (willFill > 0) {
        aggressor.applyFill(willFill);
        sink.onTrade(aggressor.orderId(), 999L, aggressor.price(), willFill);
      }
    }

    @Override
    public long fillableQty(final Order aggressor, final BookSide opposing) {
      return fillable;
    }
  }

  /** Counts callbacks so a test can assert the walk emitted, without materialising trades. */
  private static final class CountingSink implements TradeSink {
    private int trades;

    @Override
    public void onTrade(
        final long aggressorId, final long restingId, final long price, final long qty) {
      trades++;
    }
  }

  private static Order order(final OrderType type, final long qty) {
    final long price = type == OrderType.MARKET ? Prices.marketPrice(OrderSide.BUY) : 100L;
    return Order.of(1L, price, qty, OrderSide.BUY, type);
  }

  private static SubmitOutcome submit(final OrderBook book, final Order order) {
    return book.submit(order, new CountingSink());
  }

  // --- Phase 3: remainder ---------------------------------------------------------------------

  @Test
  @Requirement("FR-2.1")
  @DisplayName("FR-2.1: a limit order's remainder rests in the book at its price")
  void limit_remainder_rests() {
    final OrderBook book = new TreeMapOrderBook(new StubMatcher(4L, 4L));
    final Order limit = order(OrderType.LIMIT, 10L);

    assertThat(submit(book, limit)).isEqualTo(SubmitOutcome.RESTED);
    assertThat(book.topOfBook(OrderSide.BUY).price()).isEqualTo(100L);
    assertThat(book.topOfBook(OrderSide.BUY).qty()).isEqualTo(6L);
  }

  @Test
  @Requirement("FR-2.1")
  @DisplayName("FR-2.1: a fully filled order is terminal and does not rest")
  void fully_filled_order_does_not_rest() {
    final OrderBook book = new TreeMapOrderBook(new StubMatcher(10L, 10L));

    assertThat(submit(book, order(OrderType.LIMIT, 10L))).isEqualTo(SubmitOutcome.FILLED);
    assertThat(book.topOfBook(OrderSide.BUY).isEmpty()).isTrue();
  }

  @Test
  @Requirement("FR-2.2")
  @DisplayName("FR-2.2: a market order never rests")
  void market_order_never_rests() {
    final OrderBook book = new TreeMapOrderBook(new StubMatcher(4L, 4L));

    // Resting this would put a price sentinel in the book, where TreeMapBookSide.remove would then
    // look up a level by a meaningless key. FR-2.2 is what keeps that unreachable.
    assertThat(submit(book, order(OrderType.MARKET, 10L)))
        .isEqualTo(SubmitOutcome.REMAINDER_CANCELLED);
    assertThat(book.topOfBook(OrderSide.BUY).isEmpty()).isTrue();
  }

  @Test
  @Requirement("FR-2.3")
  @DisplayName("FR-2.3: a market order's unfilled remainder is cancelled, not dropped silently")
  void market_remainder_is_cancelled_explicitly() {
    final OrderBook book = new TreeMapOrderBook(new StubMatcher(0L, 0L));

    // Distinct from FILLED on purpose: the client asked for more than the book could give, and the
    // outcome has to say so.
    assertThat(submit(book, order(OrderType.MARKET, 10L)))
        .isEqualTo(SubmitOutcome.REMAINDER_CANCELLED);
  }

  @Test
  @Requirement("FR-2.4")
  @DisplayName("FR-2.4: IOC keeps what it filled and cancels the rest")
  void ioc_cancels_remainder() {
    final CountingSink sink = new CountingSink();
    final OrderBook book = new TreeMapOrderBook(new StubMatcher(6L, 6L));
    final Order ioc = order(OrderType.IOC, 10L);

    assertThat(book.submit(ioc, sink)).isEqualTo(SubmitOutcome.REMAINDER_CANCELLED);
    assertThat(sink.trades).isEqualTo(1);
    assertThat(ioc.filledQty()).isEqualTo(6L);
    assertThat(book.topOfBook(OrderSide.BUY).isEmpty()).isTrue();
  }

  // --- Phase 1: gate ------------------------------------------------------------------------------

  @Test
  @Requirement("FR-2.5")
  @DisplayName("FR-2.5: FOK executes in full or not at all")
  void fok_is_all_or_nothing() {
    final CountingSink sink = new CountingSink();
    final OrderBook partial = new TreeMapOrderBook(new StubMatcher(6L, 6L));
    final Order killed = order(OrderType.FOK, 10L);

    // Only 6 of 10 fillable, so the gate fires BEFORE the walk: nothing executes, nothing rests.
    // This is why FOK cannot be a remainder policy -- after the walk, those 6 could not be undone.
    assertThat(partial.submit(killed, sink)).isEqualTo(SubmitOutcome.KILLED);
    assertThat(sink.trades).isZero();
    assertThat(killed.filledQty()).isZero();
    assertThat(partial.topOfBook(OrderSide.BUY).isEmpty()).isTrue();

    final OrderBook full = new TreeMapOrderBook(new StubMatcher(10L, 10L));
    assertThat(submit(full, order(OrderType.FOK, 10L))).isEqualTo(SubmitOutcome.FILLED);
  }

  @Test
  @Requirement("FR-2.6")
  @DisplayName("FR-2.6: POST is rejected when it would cross, and rests when it would not")
  void post_only_never_takes_liquidity() {
    final CountingSink sink = new CountingSink();
    final OrderBook crossing = new TreeMapOrderBook(new StubMatcher(1L, 1L));

    // Any non-zero fillable quantity means it would take liquidity.
    assertThat(crossing.submit(order(OrderType.POST, 10L), sink))
        .isEqualTo(SubmitOutcome.REJECTED_WOULD_CROSS);
    assertThat(sink.trades).isZero();
    assertThat(crossing.topOfBook(OrderSide.BUY).isEmpty()).isTrue();

    final OrderBook resting = new TreeMapOrderBook(new StubMatcher(0L, 0L));
    assertThat(submit(resting, order(OrderType.POST, 10L))).isEqualTo(SubmitOutcome.RESTED);
    assertThat(resting.topOfBook(OrderSide.BUY).qty()).isEqualTo(10L);
  }

  @Test
  @Requirement("VR-3.1")
  @DisplayName("VR-3.1: every order type handles an empty book without corruption")
  void every_type_survives_an_empty_book() {
    for (final OrderType type : OrderType.values()) {
      final OrderBook book = new TreeMapOrderBook(new StubMatcher(0L, 0L));
      final SubmitOutcome outcome = submit(book, order(type, 10L));

      // Each type has a different right answer against no liquidity, and none of them is a crash
      // or a corrupt book: LIMIT/POST rest (nothing to cross, so nothing to reject), MARKET/IOC
      // cancel a remainder they could not fill, and FOK is killed by the gate before it walks.
      final SubmitOutcome expected =
          switch (type) {
            case LIMIT, POST -> SubmitOutcome.RESTED;
            case MARKET, IOC -> SubmitOutcome.REMAINDER_CANCELLED;
            case FOK -> SubmitOutcome.KILLED;
          };
      final boolean rests = expected == SubmitOutcome.RESTED;

      assertThat(outcome).as("outcome for %s against an empty book", type).isEqualTo(expected);
      assertThat(book.topOfBook(OrderSide.BUY).isEmpty()).isNotEqualTo(rests);
      assertThat(book.topOfBook(OrderSide.SELL).isEmpty()).isTrue();
    }
  }

  @Test
  @Requirement("FR-2.5")
  @DisplayName("FR-2.5: an FOK remainder fails loudly rather than resting")
  void fok_remainder_is_unreachable_and_fails_loudly() {
    // A matcher whose probe promises 10 but whose walk delivers 6 is broken. The book must not
    // quietly rest the remainder (turning FOK into LIMIT) or drop it (silent partial fill).
    final OrderBook book = new TreeMapOrderBook(new StubMatcher(10L, 6L));

    assertThatThrownBy(() -> submit(book, order(OrderType.FOK, 10L)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("probe and walk disagree");
  }

  @Test
  @Requirement("FR-2.6")
  @DisplayName("FR-2.6: the gate leaves the book untouched when it fires")
  void gate_rejections_do_not_touch_the_book() {
    final OrderBook book = new TreeMapOrderBook(new StubMatcher(5L, 5L));

    // A resting order first, so "untouched" means something stronger than "still empty".
    submit(book, Order.of(7L, 100L, 3L, OrderSide.BUY, OrderType.LIMIT));
    final long before = book.topOfBook(OrderSide.BUY).qty();

    submit(book, order(OrderType.POST, 10L));
    submit(book, Order.of(2L, 100L, 10L, OrderSide.BUY, OrderType.FOK));

    assertThat(book.topOfBook(OrderSide.BUY).qty()).isEqualTo(before);
  }
}
