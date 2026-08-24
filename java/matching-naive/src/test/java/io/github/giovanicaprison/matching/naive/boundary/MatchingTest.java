package io.github.giovanicaprison.matching.naive.boundary;

import static io.github.giovanicaprison.matching.naive.boundary.Engine.assertContinuous;
import static io.github.giovanicaprison.matching.naive.boundary.Engine.assertProRata;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Which resting order a taker reaches, and what the execution says. */
class MatchingTest {

  @Test
  @DisplayName("FR-3.1 resting liquidity is consumed best price first")
  void the_best_price_goes_first() {
    assertContinuous(
        """
        NEW      SELL LIMIT GTC 100010 10
        ACCEPTED #1
        RESTED   #1 SELL 100010 10

        NEW      SELL LIMIT GTC 100000 10
        ACCEPTED #2
        RESTED   #2 SELL 100000 10

        NEW      SELL LIMIT GTC 100005 10
        ACCEPTED #3
        RESTED   #3 SELL 100005 10

        NEW      BUY MARKET IOC - 25
        ACCEPTED #4
        EXECUTED @1 aggressor=#4 resting=#2 100000 10
        EXECUTED @2 aggressor=#4 resting=#3 100005 10
        EXECUTED @3 aggressor=#4 resting=#1 100010 5
        """);
  }

  @Test
  @DisplayName("FR-3.3 price-time allocation consumes in arrival order")
  void price_time_consumes_in_arrival_order() {
    assertContinuous(
        """
        NEW      BUY LIMIT GTC 100000 20
        ACCEPTED #1
        RESTED   #1 BUY 100000 20

        NEW      BUY LIMIT GTC 100000 30
        ACCEPTED #2
        RESTED   #2 BUY 100000 30

        NEW      SELL LIMIT GTC 100000 25
        ACCEPTED #3
        EXECUTED @1 aggressor=#3 resting=#1 100000 20
        EXECUTED @2 aggressor=#3 resting=#2 100000 5
        """);
  }

  @Test
  @DisplayName("FR-3.2 allocation follows the algorithm the instrument was configured with")
  void the_configured_algorithm_decides() {
    // The same arrivals as the price-time case, on a pro-rata instrument. Time no longer decides:
    // both orders give up a share, and the later one is touched before the earlier is exhausted.
    assertProRata(
        """
        NEW      BUY LIMIT GTC 100000 20
        ACCEPTED #1
        RESTED   #1 BUY 100000 20

        NEW      BUY LIMIT GTC 100000 20
        ACCEPTED #2
        RESTED   #2 BUY 100000 20

        NEW      SELL LIMIT GTC 100000 20
        ACCEPTED #3
        EXECUTED @1 aggressor=#3 resting=#1 100000 10
        EXECUTED @2 aggressor=#3 resting=#2 100000 10
        """);
  }

  @Test
  @DisplayName("FR-3.4 pro-rata rounds down to lot and gives the remainder out in arrival order")
  void pro_rata_rounds_down_and_then_queues() {
    // Thirty and ten resting, fifteen wanted. Shares are eleven and a quarter and three and three
    // quarters, so eleven and three go out and the lot the rounding left goes to whoever was first.
    assertProRata(
        """
        NEW      BUY LIMIT GTC 100000 30
        ACCEPTED #1
        RESTED   #1 BUY 100000 30

        NEW      BUY LIMIT GTC 100000 10
        ACCEPTED #2
        RESTED   #2 BUY 100000 10

        NEW      SELL LIMIT GTC 100000 15
        ACCEPTED #3
        EXECUTED @1 aggressor=#3 resting=#1 100000 11
        EXECUTED @2 aggressor=#3 resting=#2 100000 3
        EXECUTED @3 aggressor=#3 resting=#1 100000 1
        """);
  }

  @Test
  @DisplayName("FR-3.5 an execution happens at the resting order's price")
  void the_resting_price_is_the_execution_price() {
    assertContinuous(
        """
        NEW      SELL LIMIT GTC 100000 50
        ACCEPTED #1
        RESTED   #1 SELL 100000 50

        NEW      BUY LIMIT GTC 100025 50
        ACCEPTED #2
        EXECUTED @1 aggressor=#2 resting=#1 100000 50
        """);
  }

  @Test
  @DisplayName("FR-3.6 each execution reports an id of its own, both orders, price and quantity")
  void every_execution_is_identified() {
    assertContinuous(
        """
        NEW      SELL LIMIT GTC 100000 10
        ACCEPTED #1
        RESTED   #1 SELL 100000 10

        NEW      SELL LIMIT GTC 100005 10
        ACCEPTED #2
        RESTED   #2 SELL 100005 10

        NEW      BUY LIMIT GTC 100005 20
        ACCEPTED #3
        EXECUTED @1 aggressor=#3 resting=#1 100000 10
        EXECUTED @2 aggressor=#3 resting=#2 100005 10
        """);
  }

  @Test
  @DisplayName("FR-3.7 an order never executes against a resting order sharing its self match id")
  void self_matching_is_prevented() {
    assertContinuous(
        """
        NEW      BUY LIMIT GTC 100000 30 smp=7
        ACCEPTED #1
        RESTED   #1 BUY 100000 30

        NEW      BUY LIMIT GTC 100000 20 smp=9
        ACCEPTED #2
        RESTED   #2 BUY 100000 20

        NEW      SELL LIMIT GTC 100000 50 smp=7
        ACCEPTED #3
        REMOVED  #1 30 SELF_MATCH_PREVENTED
        EXECUTED @1 aggressor=#3 resting=#2 100000 20
        RESTED   #3 SELL 100000 30
        """);
  }
}
