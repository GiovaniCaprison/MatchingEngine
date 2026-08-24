package io.github.giovanicaprison.matching.naive.boundary;

import static io.github.giovanicaprison.matching.naive.boundary.Engine.assertContinuous;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Icebergs: what the feed is told, and what a taker can reach. */
class HiddenQuantityTest {

  @Test
  @DisplayName("FR-5.1 an order may display less than its total quantity")
  void an_order_can_hide_most_of_itself() {
    assertContinuous(
        """
        NEW      BUY LIMIT GTC 100000 100 display=40
        ACCEPTED #1
        RESTED   #1 BUY 100000 40
        """);
  }

  @Test
  @DisplayName("FR-5.2 only displayed quantity appears as resting in the output stream")
  void the_feed_never_learns_the_hidden_part() {
    // The rest says forty and the removal says forty. A consumer's book never held the hundred, so
    // a removal of a hundred would take it negative.
    assertContinuous(
        """
        NEW      BUY LIMIT GTC 100000 100 display=40
        ACCEPTED #1
        RESTED   #1 BUY 100000 40

        CANCEL   #1
        REMOVED  #1 40 CANCELLED
        """);
  }

  @Test
  @DisplayName("FR-5.3 displayed quantity at a price is consumed before hidden quantity")
  void displayed_goes_before_hidden() {
    // The iceberg arrived first, so its displayed ten goes first. What follows is the other order's
    // displayed fifty rather than the iceberg's hidden ninety.
    assertContinuous(
        """
        NEW      BUY LIMIT GTC 100000 100 display=10
        ACCEPTED #1
        RESTED   #1 BUY 100000 10

        NEW      BUY LIMIT GTC 100000 50
        ACCEPTED #2
        RESTED   #2 BUY 100000 50

        NEW      SELL LIMIT GTC 100000 60
        ACCEPTED #3
        EXECUTED @1 aggressor=#3 resting=#1 100000 10
        RESTED   #1 BUY 100000 10
        EXECUTED @2 aggressor=#3 resting=#2 100000 50
        """);
  }

  @Test
  @DisplayName("FR-5.4 an exhausted display is replenished at the back of the queue at its price")
  void a_replenished_tranche_goes_to_the_back() {
    // Alone at its price, the iceberg is reached again immediately, and each tranche appears as a
    // fresh rest. To a consumer that is a new order arriving there, which is the point of an
    // iceberg.
    assertContinuous(
        """
        NEW      BUY LIMIT GTC 100000 30 display=10
        ACCEPTED #1
        RESTED   #1 BUY 100000 10

        NEW      SELL LIMIT GTC 100000 25
        ACCEPTED #2
        EXECUTED @1 aggressor=#2 resting=#1 100000 10
        RESTED   #1 BUY 100000 10
        EXECUTED @2 aggressor=#2 resting=#1 100000 10
        RESTED   #1 BUY 100000 10
        EXECUTED @3 aggressor=#2 resting=#1 100000 5
        """);
  }
}
