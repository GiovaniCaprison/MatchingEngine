package io.github.giovanicaprison.matching.naive;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.giovanicaprison.matching.api.Instrument;
import io.github.giovanicaprison.matching.protocol.PricingInstruction;
import io.github.giovanicaprison.matching.protocol.Side;
import io.github.giovanicaprison.matching.protocol.TimeInForce;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the engine has to get right, stated once each.
 *
 * <p>These are the ordinary unit tests for this module, not a substitute for the conformance
 * corpus. The corpus is where long interleaved sequences belong and where every implementation is
 * held to identical output. These say what each rule is, in a form a reader can check against
 * REQUIREMENTS.md.
 */
class NaiveMatchingEngineTest {

  private static final Instrument INSTRUMENT = new Instrument(1, 5L, 1L, 100L, 1_000_000L, 4);

  private EngineHarness engine;

  @BeforeEach
  void setUp() {
    engine = new EngineHarness(INSTRUMENT);
  }

  @Test
  @DisplayName("FR-2.1: an order that crosses nothing rests, and says so")
  void unmatched_limit_order_rests() {
    final long id = engine.limit(Side.SELL, 1000L, 50L);

    assertThat(engine.events())
        .containsExactly("ACCEPTED order=1 client=1", "RESTED order=1 side=SELL price=1000 qty=50");
    assertThat(id).isEqualTo(1L);
  }

  @Test
  @DisplayName("FR-3.3: an execution happens at the resting order's price")
  void execution_takes_the_resting_price() {
    engine.limit(Side.SELL, 1000L, 50L);
    engine.limit(Side.BUY, 1050L, 50L);

    assertThat(engine.events())
        .containsExactly(
            "ACCEPTED order=2 client=2", "EXECUTED exec=1 aggressor=2 resting=1 price=1000 qty=50");
  }

  @Test
  @DisplayName("FR-3.1: the best price is taken first")
  void price_priority() {
    engine.limit(Side.SELL, 1005L, 10L);
    engine.limit(Side.SELL, 1000L, 10L);

    engine.limit(Side.BUY, 1005L, 20L);

    assertThat(engine.events())
        .containsExactly(
            "ACCEPTED order=3 client=3",
            "EXECUTED exec=1 aggressor=3 resting=2 price=1000 qty=10",
            "EXECUTED exec=2 aggressor=3 resting=1 price=1005 qty=10");
  }

  @Test
  @DisplayName("FR-3.2: at one price, the earlier order is taken first")
  void time_priority() {
    engine.limit(Side.SELL, 1000L, 10L);
    engine.limit(Side.SELL, 1000L, 10L);

    engine.limit(Side.BUY, 1000L, 15L);

    assertThat(engine.events())
        .containsExactly(
            "ACCEPTED order=3 client=3",
            "EXECUTED exec=1 aggressor=3 resting=1 price=1000 qty=10",
            "EXECUTED exec=2 aggressor=3 resting=2 price=1000 qty=5");
  }

  @Test
  @DisplayName("FR-2.2: a market order sweeps and its remainder is removed rather than rested")
  void market_order_never_rests() {
    engine.limit(Side.SELL, 1000L, 10L);
    engine.newOrder(
        Side.BUY, PricingInstruction.MARKET, TimeInForce.IMMEDIATE_OR_CANCEL, false, 0L, 25L);

    assertThat(engine.events())
        .containsExactly(
            "ACCEPTED order=2 client=2",
            "EXECUTED exec=1 aggressor=2 resting=1 price=1000 qty=10",
            "REMOVED order=2 qty=15 reason=IMMEDIATE_OR_CANCEL_REMAINDER");
  }

  @Test
  @DisplayName("FR-2.4: a fill-or-kill that cannot fill in full is refused and takes nothing")
  void fill_or_kill_is_all_or_nothing() {
    engine.limit(Side.SELL, 1000L, 10L);

    engine.newOrder(
        Side.BUY, PricingInstruction.LIMIT, TimeInForce.FILL_OR_KILL, false, 1000L, 25L);
    assertThat(engine.events()).containsExactly("REJECTED client=2 reason=FILL_OR_KILL_UNFILLABLE");

    // The resting order is untouched, which is the part worth asserting: a kill that consumed
    // liquidity before discovering it could not finish would be unrecoverable.
    engine.newOrder(
        Side.BUY, PricingInstruction.LIMIT, TimeInForce.FILL_OR_KILL, false, 1000L, 10L);
    assertThat(engine.events())
        .containsExactly(
            "ACCEPTED order=2 client=3", "EXECUTED exec=1 aggressor=2 resting=1 price=1000 qty=10");
  }

  @Test
  @DisplayName("FR-2.5: a post-only order that would take liquidity is refused")
  void post_only_never_takes() {
    engine.limit(Side.SELL, 1000L, 10L);

    engine.newOrder(
        Side.BUY, PricingInstruction.LIMIT, TimeInForce.GOOD_TILL_CANCEL, true, 1000L, 10L);
    assertThat(engine.events()).containsExactly("REJECTED client=2 reason=WOULD_CROSS");

    engine.newOrder(
        Side.BUY, PricingInstruction.LIMIT, TimeInForce.GOOD_TILL_CANCEL, true, 995L, 10L);
    assertThat(engine.events())
        .containsExactly("ACCEPTED order=2 client=3", "RESTED order=2 side=BUY price=995 qty=10");
  }

  @Test
  @DisplayName("FR-4.1, FR-4.2: a cancel removes the order, and a second one is reported")
  void cancel_removes_once() {
    final long id = engine.limit(Side.SELL, 1000L, 10L);

    engine.cancel(id);
    assertThat(engine.events()).containsExactly("REMOVED order=1 qty=10 reason=CANCELLED");

    engine.cancel(id);
    assertThat(engine.events()).containsExactly("REJECTED client=3 reason=UNKNOWN_ORDER");
  }

  @Test
  @DisplayName("FR-4.4: lowering quantity at the same price keeps queue position")
  void replace_down_keeps_position() {
    final long first = engine.limit(Side.SELL, 1000L, 20L);
    engine.limit(Side.SELL, 1000L, 20L);

    engine.replace(first, 10L, 1000L);
    assertThat(engine.events()).containsExactly("REDUCED order=1 qty=10");

    // Still ahead of the second order, which is the whole claim.
    engine.limit(Side.BUY, 1000L, 10L);
    assertThat(engine.events())
        .containsExactly(
            "ACCEPTED order=3 client=4", "EXECUTED exec=1 aggressor=3 resting=1 price=1000 qty=10");
  }

  @Test
  @DisplayName("FR-4.5: any other replace loses queue position, reported as a removal then a rest")
  void replace_up_loses_position() {
    final long first = engine.limit(Side.SELL, 1000L, 20L);
    engine.limit(Side.SELL, 1000L, 20L);

    engine.replace(first, 40L, 1000L);
    assertThat(engine.events())
        .containsExactly(
            "REMOVED order=1 qty=20 reason=REPLACED", "RESTED order=1 side=SELL price=1000 qty=40");

    // Behind the second order now.
    engine.limit(Side.BUY, 1000L, 20L);
    assertThat(engine.events())
        .containsExactly(
            "ACCEPTED order=3 client=4", "EXECUTED exec=1 aggressor=3 resting=2 price=1000 qty=20");
  }

  @Test
  @DisplayName("VR: an invalid order is refused and the book is untouched")
  void validation_refuses_before_touching_state() {
    engine.limit(Side.SELL, 1000L, 10L);

    assertThat(refusalFor(0L, 1000L)).isEqualTo("NON_POSITIVE_QUANTITY");
    assertThat(refusalFor(10L, 1001L)).isEqualTo("TICK_VIOLATION");
    assertThat(refusalFor(10L, 0L)).isEqualTo("NON_POSITIVE_PRICE");
    assertThat(refusalFor(10L, 95L)).isEqualTo("BAND_VIOLATION");

    // Four refusals later the resting order is still there and still first in line. The aggressor
    // is
    // order 2 rather than order 6, because a refused order never reaches the book and so never
    // consumes an order id. Client order ids do advance, which is what a client correlates on.
    engine.limit(Side.BUY, 1000L, 10L);
    assertThat(engine.events())
        .containsExactly(
            "ACCEPTED order=2 client=6", "EXECUTED exec=1 aggressor=2 resting=1 price=1000 qty=10");
  }

  @Test
  @DisplayName("VR-3.1: a market order that claims it can rest is refused")
  void contradictory_fields_are_refused() {
    // A market order cannot rest and cannot avoid taking, so a resting time in force contradicts it
    // rather than being silently reinterpreted.
    engine.newOrder(
        Side.BUY, PricingInstruction.MARKET, TimeInForce.GOOD_TILL_CANCEL, false, 0L, 10L);
    assertThat(engine.events()).containsExactly("REJECTED client=1 reason=INVALID_FIELDS");

    engine.newOrder(
        Side.BUY, PricingInstruction.MARKET, TimeInForce.IMMEDIATE_OR_CANCEL, true, 0L, 10L);
    assertThat(engine.events()).containsExactly("REJECTED client=2 reason=INVALID_FIELDS");
  }

  private String refusalFor(final long quantity, final long price) {
    engine.limit(Side.BUY, price, quantity);
    final String line = engine.events().get(0);
    return line.substring(line.indexOf("reason=") + "reason=".length());
  }
}
