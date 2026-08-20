package com.imc.me.boundary;

import static org.assertj.core.api.Assertions.assertThat;

import com.imc.me.MatchingEngine;
import com.imc.me.domain.Instrument;
import com.imc.me.domain.OrderSide;
import com.imc.me.domain.OrderType;
import com.imc.me.domain.Trade;
import com.imc.me.event.command.NewOrder;
import com.imc.me.event.result.Accepted;
import com.imc.me.event.result.Rejected;
import com.imc.me.event.result.SubmitOutcome;
import com.imc.me.event.result.SubmitResult;
import com.imc.me.support.AcrossBooks;
import com.imc.me.support.BookImplementations.Named;

/**
 * The walk, driven through the public API only, against every book implementation.
 *
 * <p>These are the boundary examples TESTING.md calls for: small, hand-asserted, one behaviour
 * each. They are not a substitute for the scenario corpus, which is where the rich multi-level
 * cases belong, and they are not a substitute for a reference model. What they add is a readable
 * statement of each rule, in a form a new implementation can be held to before anybody measures it.
 */
class PriceTimeWalkTest {

  private static final Instrument INST = new Instrument(1, "TEST", 1L, 1L, 4);

  private static MatchingEngine engine(final Named impl) {
    return new MatchingEngine(INST, impl.book().get());
  }

  private static NewOrder order(
      final long cid, final OrderSide s, final OrderType t, final long price, final long qty) {
    return new NewOrder(cid, s, t, qty, price);
  }

  private static Accepted ok(final SubmitResult r) {
    assertThat(r).isInstanceOf(Accepted.class);
    return (Accepted) r;
  }

  @AcrossBooks
  void fr_3_1_price_priority_sweep(final Named impl) {
    var e = engine(impl);
    ok(e.submit(order(1, OrderSide.SELL, OrderType.LIMIT, 100, 5)));
    ok(e.submit(order(2, OrderSide.SELL, OrderType.LIMIT, 101, 5)));

    var a = ok(e.submit(order(3, OrderSide.BUY, OrderType.LIMIT, 101, 8)));

    assertThat(a.fills().size()).isEqualTo(2);
    assertThat(a.fills().get(0).price()).isEqualTo(100L);
    assertThat(a.fills().get(0).qty()).isEqualTo(5L);
    assertThat(a.fills().get(1).price()).isEqualTo(101L);
    assertThat(a.fills().get(1).qty()).isEqualTo(3L);
    assertThat(a.outcome()).isEqualTo(SubmitOutcome.FILLED);

    var ask = e.topOfBook(OrderSide.SELL);
    assertThat(ask.isEmpty()).isFalse();
    assertThat(ask.price()).isEqualTo(101L);
    assertThat(ask.qty()).isEqualTo(2L);
    assertThat(e.topOfBook(OrderSide.BUY).isEmpty()).isTrue();
  }

  @AcrossBooks
  void fr_3_2_time_priority_is_fifo(final Named impl) {
    var e = engine(impl);
    var first = ok(e.submit(order(1, OrderSide.SELL, OrderType.LIMIT, 100, 5)));
    var second = ok(e.submit(order(2, OrderSide.SELL, OrderType.LIMIT, 100, 5)));

    var a = ok(e.submit(order(3, OrderSide.BUY, OrderType.LIMIT, 100, 5)));

    assertThat(a.fills().size()).isEqualTo(1);
    assertThat(a.fills().get(0).restingId()).isEqualTo(first.orderId());
    assertThat(e.status(second.orderId()).orElseThrow().remainingQty()).isEqualTo(5L);
  }

  @AcrossBooks
  void fr_3_5_price_improvement_goes_to_the_aggressor(final Named impl) {
    var e = engine(impl);
    ok(e.submit(order(1, OrderSide.SELL, OrderType.LIMIT, 100, 5)));
    var a = ok(e.submit(order(2, OrderSide.BUY, OrderType.LIMIT, 105, 5)));
    assertThat(a.fills().get(0).price()).isEqualTo(100L);
  }

  @AcrossBooks
  void partial_fill_rests_the_remainder(final Named impl) {
    var e = engine(impl);
    ok(e.submit(order(1, OrderSide.SELL, OrderType.LIMIT, 100, 3)));
    var a = ok(e.submit(order(2, OrderSide.BUY, OrderType.LIMIT, 100, 10)));
    assertThat(a.outcome()).isEqualTo(SubmitOutcome.RESTED);
    assertThat(e.topOfBook(OrderSide.BUY).qty()).isEqualTo(7L);
  }

  @AcrossBooks
  void fr_2_2_market_order_sweeps_and_never_rests(final Named impl) {
    var e = engine(impl);
    ok(e.submit(order(1, OrderSide.SELL, OrderType.LIMIT, 100, 2)));
    ok(e.submit(order(2, OrderSide.SELL, OrderType.LIMIT, 101, 2)));
    var a = ok(e.submit(order(3, OrderSide.BUY, OrderType.MARKET, 0, 10)));

    assertThat(a.fills().size()).isEqualTo(2);
    assertThat(a.outcome()).isEqualTo(SubmitOutcome.REMAINDER_CANCELLED);
    assertThat(e.topOfBook(OrderSide.BUY).isEmpty()).isTrue();
    assertThat(e.topOfBook(OrderSide.SELL).isEmpty()).isTrue();
  }

  @AcrossBooks
  void fr_2_5_fok_fills_in_full_or_not_at_all(final Named impl) {
    var e = engine(impl);
    ok(e.submit(order(1, OrderSide.SELL, OrderType.LIMIT, 100, 4)));

    var killed = e.submit(order(2, OrderSide.BUY, OrderType.FOK, 100, 10));
    assertThat(killed).isInstanceOf(Rejected.class);
    assertThat(e.topOfBook(OrderSide.SELL).qty()).isEqualTo(4L);

    var filled = ok(e.submit(order(3, OrderSide.BUY, OrderType.FOK, 100, 4)));
    assertThat(filled.outcome()).isEqualTo(SubmitOutcome.FILLED);
    assertThat(e.topOfBook(OrderSide.SELL).isEmpty()).isTrue();
  }

  @AcrossBooks
  void fr_2_6_post_only_never_takes_liquidity(final Named impl) {
    var e = engine(impl);
    ok(e.submit(order(1, OrderSide.SELL, OrderType.LIMIT, 100, 5)));

    var crossed = e.submit(order(2, OrderSide.BUY, OrderType.POST, 100, 5));
    assertThat(crossed).isInstanceOf(Rejected.class);
    assertThat(e.topOfBook(OrderSide.SELL).qty()).isEqualTo(5L);

    var rested = ok(e.submit(order(3, OrderSide.BUY, OrderType.POST, 99, 5)));
    assertThat(rested.outcome()).isEqualTo(SubmitOutcome.RESTED);
    assertThat(e.topOfBook(OrderSide.BUY).price()).isEqualTo(99L);
  }

  @AcrossBooks
  void fr_2_4_ioc_takes_what_it_can_and_cancels_the_rest(final Named impl) {
    var e = engine(impl);
    ok(e.submit(order(1, OrderSide.SELL, OrderType.LIMIT, 100, 3)));
    var a = ok(e.submit(order(2, OrderSide.BUY, OrderType.IOC, 100, 10)));
    assertThat(a.fills().get(0).qty()).isEqualTo(3L);
    assertThat(a.outcome()).isEqualTo(SubmitOutcome.REMAINDER_CANCELLED);
    assertThat(e.topOfBook(OrderSide.BUY).isEmpty()).isTrue();
  }

  @AcrossBooks
  void nfr_1_1_trades_carry_a_gapless_sequence(final Named impl) {
    var e = engine(impl);
    ok(e.submit(order(1, OrderSide.SELL, OrderType.LIMIT, 100, 1)));
    ok(e.submit(order(2, OrderSide.SELL, OrderType.LIMIT, 101, 1)));
    var a = ok(e.submit(order(3, OrderSide.BUY, OrderType.LIMIT, 101, 2)));

    Trade t0 = a.fills().get(0);
    Trade t1 = a.fills().get(1);
    assertThat(t1.sequence()).isEqualTo(t0.sequence() + 1);
    assertThat(t0.sequence()).isGreaterThan(0L);
  }

  @AcrossBooks
  void does_not_cross_when_prices_do_not_meet(final Named impl) {
    var e = engine(impl);
    ok(e.submit(order(1, OrderSide.SELL, OrderType.LIMIT, 101, 5)));
    var a = ok(e.submit(order(2, OrderSide.BUY, OrderType.LIMIT, 100, 5)));
    assertThat(a.fills().isEmpty()).isTrue();
    assertThat(a.outcome()).isEqualTo(SubmitOutcome.RESTED);
    assertThat(e.topOfBook(OrderSide.BUY).price()).isEqualTo(100L);
    assertThat(e.topOfBook(OrderSide.SELL).price()).isEqualTo(101L);
  }
}
