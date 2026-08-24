package io.github.giovanicaprison.matching.naive.boundary;

import static io.github.giovanicaprison.matching.naive.boundary.Engine.assertContinuous;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Stops: waiting outside the book, and what they become on firing. */
class StopOrderTest {

  @Test
  @DisplayName("FR-6.1 a stop rests in the trigger book and is not book liquidity")
  void a_stop_is_not_liquidity() {
    // Accepted, never rested, and invisible to a taker: the sell finds an empty book.
    assertContinuous(
        """
        NEW      BUY MARKET IOC - 50 trigger=100500
        ACCEPTED #1

        NEW      SELL MARKET IOC - 50
        ACCEPTED #2
        REMOVED  #2 50 IMMEDIATE_OR_CANCEL_REMAINDER
        """);
  }

  @Test
  @DisplayName("FR-6.2 a stop triggers when the executed price reaches it, in its side's direction")
  void a_stop_fires_in_one_direction() {
    // The buy stop fires as the price rises to it. The sell stop below the market does not, because
    // nothing has fallen to it.
    assertContinuous(
        """
        NEW      BUY LIMIT GTC 100015 30 trigger=100010
        ACCEPTED #1

        NEW      SELL MARKET IOC - 10 trigger=99990
        ACCEPTED #2

        NEW      SELL LIMIT GTC 100010 20
        ACCEPTED #3
        RESTED   #3 SELL 100010 20

        NEW      BUY LIMIT GTC 100010 20
        ACCEPTED  #4
        EXECUTED  @1 aggressor=#4 resting=#3 100010 20
        TRIGGERED #1
        RESTED    #1 BUY 100015 30
        """);
  }

  @Test
  @DisplayName("FR-6.3 a triggered stop enters the book as an order of its pricing instruction")
  void a_triggered_stop_is_what_it_said_it_was() {
    // A stop-market, so on firing it takes the price it finds rather than the one it named, and its
    // remainder leaves.
    assertContinuous(
        """
        NEW      BUY MARKET IOC - 30 trigger=100010
        ACCEPTED #1

        NEW      SELL LIMIT GTC 100015 10
        ACCEPTED #2
        RESTED   #2 SELL 100015 10

        NEW      SELL LIMIT GTC 100010 20
        ACCEPTED #3
        RESTED   #3 SELL 100010 20

        NEW       BUY LIMIT GTC 100010 20
        ACCEPTED  #4
        EXECUTED  @1 aggressor=#4 resting=#3 100010 20
        TRIGGERED #1
        EXECUTED  @2 aggressor=#1 resting=#2 100015 10
        REMOVED   #1 20 IMMEDIATE_OR_CANCEL_REMAINDER
        """);
  }

  @Test
  @DisplayName("FR-6.4 a cascade runs to completion before the next command is applied")
  void one_stop_can_fire_another() {
    assertContinuous(
        """
        NEW      BUY MARKET IOC - 10 trigger=100010
        ACCEPTED #1

        NEW      BUY MARKET IOC - 10 trigger=100015
        ACCEPTED #2

        NEW      SELL LIMIT GTC 100015 10
        ACCEPTED #3
        RESTED   #3 SELL 100015 10

        NEW      SELL LIMIT GTC 100020 10
        ACCEPTED #4
        RESTED   #4 SELL 100020 10

        NEW      SELL LIMIT GTC 100010 10
        ACCEPTED #5
        RESTED   #5 SELL 100010 10

        NEW       BUY LIMIT GTC 100010 10
        ACCEPTED  #6
        EXECUTED  @1 aggressor=#6 resting=#5 100010 10
        TRIGGERED #1
        EXECUTED  @2 aggressor=#1 resting=#3 100015 10
        TRIGGERED #2
        EXECUTED  @3 aggressor=#2 resting=#4 100020 10
        """);
  }

  @Test
  @DisplayName("FR-6.5 a stop is reported on acceptance, on triggering and on cancellation")
  void a_stop_is_reported_at_every_step() {
    assertContinuous(
        """
        NEW      BUY MARKET IOC - 30 trigger=100500
        ACCEPTED #1
        CANCEL   #1
        REMOVED  #1 30 CANCELLED

        NEW      SELL LIMIT GTC 100000 10
        ACCEPTED #2
        RESTED   #2 SELL 100000 10

        NEW      BUY MARKET IOC - 10 trigger=100000
        ACCEPTED #3

        NEW       BUY LIMIT GTC 100000 10
        ACCEPTED  #4
        EXECUTED  @1 aggressor=#4 resting=#2 100000 10
        TRIGGERED #3
        REMOVED   #3 10 IMMEDIATE_OR_CANCEL_REMAINDER
        """);
  }
}
