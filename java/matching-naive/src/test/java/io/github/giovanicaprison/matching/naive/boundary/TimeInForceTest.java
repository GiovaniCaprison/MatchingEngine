package io.github.giovanicaprison.matching.naive.boundary;

import static io.github.giovanicaprison.matching.naive.boundary.Engine.assertContinuous;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What happens to the part of an order the book could not fill. */
class TimeInForceTest {

  @Test
  @DisplayName("FR-2.1 a limit order's unmatched remainder rests at its own price")
  void a_limit_remainder_rests_at_its_own_price() {
    assertContinuous(
        """
        NEW      SELL LIMIT GTC 100000 20
        ACCEPTED #1
        RESTED   #1 SELL 100000 20

        NEW      BUY LIMIT GTC 100005 50
        ACCEPTED #2
        EXECUTED @1 aggressor=#2 resting=#1 100000 20
        RESTED   #2 BUY 100005 30
        """);
  }

  @Test
  @DisplayName("FR-2.2 a market order never rests")
  void a_market_order_never_rests() {
    assertContinuous(
        """
        NEW      SELL LIMIT GTC 100000 20
        ACCEPTED #1
        RESTED   #1 SELL 100000 20

        NEW      BUY MARKET IOC - 50
        ACCEPTED #2
        EXECUTED @1 aggressor=#2 resting=#1 100000 20
        REMOVED  #2 30 IMMEDIATE_OR_CANCEL_REMAINDER
        """);
  }

  @Test
  @DisplayName("FR-2.3 an immediate-or-cancel remainder is removed")
  void an_immediate_or_cancel_remainder_is_removed() {
    assertContinuous(
        """
        NEW      SELL LIMIT GTC 100000 20
        ACCEPTED #1
        RESTED   #1 SELL 100000 20

        NEW      BUY LIMIT IOC 100000 50
        ACCEPTED #2
        EXECUTED @1 aggressor=#2 resting=#1 100000 20
        REMOVED  #2 30 IMMEDIATE_OR_CANCEL_REMAINDER
        """);
  }

  @Test
  @DisplayName("FR-2.4 a fill-or-kill order executes in full or not at all")
  void fill_or_kill_is_all_or_nothing() {
    // Refused rather than removed, because nothing was executed and nothing rested. The market
    // order
    // afterwards proves the twenty is still there, so the kill left the book alone.
    assertContinuous(
        """
        NEW      SELL LIMIT GTC 100000 20
        ACCEPTED #1
        RESTED   #1 SELL 100000 20

        NEW      BUY LIMIT FOK 100000 50
        REJECTED #2 FILL_OR_KILL_UNFILLABLE

        NEW      BUY MARKET IOC - 20
        ACCEPTED #3
        EXECUTED @1 aggressor=#3 resting=#1 100000 20
        """);
  }

  @Test
  @DisplayName("FR-2.5 a post-only order never takes liquidity, and is refused if it would")
  void post_only_never_takes() {
    assertContinuous(
        """
        NEW      BUY LIMIT GTC 100000 50
        ACCEPTED #1
        RESTED   #1 BUY 100000 50

        NEW      SELL LIMIT GTC 99995 50 POST_ONLY
        REJECTED #2 WOULD_CROSS

        NEW      SELL LIMIT GTC 100005 50 POST_ONLY
        ACCEPTED #3
        RESTED   #3 SELL 100005 50
        """);
  }

  @Test
  @DisplayName("FR-2.6 a minimum quantity is met on entry or the order is refused")
  void a_minimum_quantity_is_met_or_refused() {
    assertContinuous(
        """
        NEW      SELL LIMIT GTC 100000 20
        ACCEPTED #1
        RESTED   #1 SELL 100000 20

        NEW      BUY LIMIT IOC 100000 50 min=30
        REJECTED #2 MINIMUM_QUANTITY_NOT_MET

        NEW      BUY LIMIT IOC 100000 50 min=20
        ACCEPTED #3
        EXECUTED @1 aggressor=#3 resting=#1 100000 20
        REMOVED  #3 30 IMMEDIATE_OR_CANCEL_REMAINDER
        """);
  }
}
