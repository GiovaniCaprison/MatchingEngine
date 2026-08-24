package io.github.giovanicaprison.matching.naive.boundary;

import static io.github.giovanicaprison.matching.naive.boundary.Engine.assertContinuous;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Changing an order that is already resting, and removing one. */
class AmendTest {

  @Test
  @DisplayName("FR-4.1 a resting order can be cancelled by its engine order id")
  void an_order_can_be_cancelled() {
    assertContinuous(
        """
        NEW      BUY LIMIT GTC 100000 50
        ACCEPTED #1
        RESTED   #1 BUY 100000 50

        CANCEL   #1
        REMOVED  #1 50 CANCELLED
        """);
  }

  @Test
  @DisplayName("FR-4.2 cancelling an order the engine is not resting is reported")
  void cancelling_nothing_is_reported() {
    assertContinuous(
        """
        NEW      BUY LIMIT GTC 100000 50
        ACCEPTED #1
        RESTED   #1 BUY 100000 50

        CANCEL   #1
        REMOVED  #1 50 CANCELLED

        CANCEL   #1
        REJECTED #1 UNKNOWN_ORDER
        """);
  }

  @Test
  @DisplayName("FR-4.3 a replace carries the full intended new state rather than a delta")
  void a_replace_is_not_a_delta() {
    // Forty means forty. Read as a delta it would mean ten.
    assertContinuous(
        """
        NEW      BUY LIMIT GTC 100000 50
        ACCEPTED #1
        RESTED   #1 BUY 100000 50

        REPLACE  #1 40 100000
        REDUCED  #1 40
        """);
  }

  @Test
  @DisplayName("FR-4.4 a replace lowering quantity at the same price keeps queue position")
  void lowering_quantity_keeps_position() {
    // The order behind stays behind: the sell takes all thirty of the reduced order first.
    assertContinuous(
        """
        NEW      BUY LIMIT GTC 100000 50
        ACCEPTED #1
        RESTED   #1 BUY 100000 50

        NEW      BUY LIMIT GTC 100000 20
        ACCEPTED #2
        RESTED   #2 BUY 100000 20

        REPLACE  #1 30 100000
        REDUCED  #1 30

        NEW      SELL LIMIT GTC 100000 30
        ACCEPTED #3
        EXECUTED @1 aggressor=#3 resting=#1 100000 30
        """);
  }

  @Test
  @DisplayName("FR-4.5 any other replace loses queue position")
  void anything_else_loses_position() {
    assertContinuous(
        """
        NEW      BUY LIMIT GTC 100000 50
        ACCEPTED #1
        RESTED   #1 BUY 100000 50

        NEW      BUY LIMIT GTC 100000 20
        ACCEPTED #2
        RESTED   #2 BUY 100000 20

        REPLACE  #1 60 100000
        REMOVED  #1 50 REPLACED
        RESTED   #1 BUY 100000 60

        NEW      SELL LIMIT GTC 100000 20
        ACCEPTED #3
        EXECUTED @1 aggressor=#3 resting=#2 100000 20
        """);
  }

  @Test
  @DisplayName("FR-4.6 a replace refused by a liquidity flag leaves the original order resting")
  void a_refused_replace_leaves_the_order_alone() {
    assertContinuous(
        """
        NEW      BUY LIMIT GTC 100000 50
        ACCEPTED #1
        RESTED   #1 BUY 100000 50

        NEW      SELL LIMIT GTC 100005 50 POST_ONLY
        ACCEPTED #2
        RESTED   #2 SELL 100005 50

        REPLACE  #2 50 100000
        REJECTED #2 WOULD_CROSS

        NEW      BUY LIMIT GTC 100005 50
        ACCEPTED #3
        EXECUTED @1 aggressor=#3 resting=#2 100005 50
        """);
  }

  @Test
  @DisplayName("FR-4.7 a mass cancel removes a participant's orders in arrival order")
  void a_mass_cancel_takes_one_participant() {
    assertContinuous(
        """
        NEW        BUY LIMIT GTC 100000 10 p=2
        ACCEPTED   #1
        RESTED     #1 BUY 100000 10

        NEW        BUY LIMIT GTC 99995 20 p=3
        ACCEPTED   #2
        RESTED     #2 BUY 99995 20

        NEW        BUY LIMIT GTC 99990 30 p=2
        ACCEPTED   #3
        RESTED     #3 BUY 99990 30

        MASSCANCEL p=2
        REMOVED    #1 10 MASS_CANCELLED
        REMOVED    #3 30 MASS_CANCELLED

        NEW        SELL MARKET IOC - 20
        ACCEPTED   #4
        EXECUTED   @1 aggressor=#4 resting=#2 99995 20
        """);
  }

  @Test
  @DisplayName("FR-4.8 a replaced order keeps its engine order id")
  void the_id_survives_a_replace() {
    // The rest after the replace names the same order, and the cancel afterwards reaches it by the
    // id the client already had. A new id would leave the client with no way to learn it.
    assertContinuous(
        """
        NEW      BUY LIMIT GTC 100000 50
        ACCEPTED #1
        RESTED   #1 BUY 100000 50

        REPLACE  #1 60 99995
        REMOVED  #1 50 REPLACED
        RESTED   #1 BUY 99995 60

        CANCEL   #1
        REMOVED  #1 60 CANCELLED
        """);
  }
}
