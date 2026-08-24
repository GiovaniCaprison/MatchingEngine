package io.github.giovanicaprison.matching.naive.boundary;

import static io.github.giovanicaprison.matching.naive.boundary.Engine.assertContinuous;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What the stream says about the book, which is all a consumer ever learns. */
class OutputStreamTest {

  @Test
  @DisplayName(
      "FR-8.3 an order entering the book is reported with side, price and displayed quantity")
  void a_rest_carries_what_a_book_needs() {
    // Side, price and quantity, and for an iceberg the quantity is the displayed part.
    assertContinuous(
        """
        NEW      SELL LIMIT GTC 100005 50
        ACCEPTED #1
        RESTED   #1 SELL 100005 50

        NEW      BUY LIMIT GTC 99995 80 display=25
        ACCEPTED #2
        RESTED   #2 BUY 99995 25
        """);
  }

  @Test
  @DisplayName("FR-8.4 an order leaving the book is reported with the quantity removed and why")
  void a_removal_carries_its_reason() {
    assertContinuous(
        """
        NEW      BUY LIMIT GTC 100000 50
        ACCEPTED #1
        RESTED   #1 BUY 100000 50

        CANCEL   #1
        REMOVED  #1 50 CANCELLED

        NEW      SELL LIMIT IOC 100000 30
        ACCEPTED #2
        REMOVED  #2 30 IMMEDIATE_OR_CANCEL_REMAINDER
        """);
  }

  @Test
  @DisplayName(
      "FR-8.5 a quantity reduction that keeps queue position is reported without a removal")
  void a_reduction_is_not_a_removal() {
    assertContinuous(
        """
        NEW      BUY LIMIT GTC 100000 50
        ACCEPTED #1
        RESTED   #1 BUY 100000 50

        REPLACE  #1 20 100000
        REDUCED  #1 20

        CANCEL   #1
        REMOVED  #1 20 CANCELLED
        """);
  }
}
