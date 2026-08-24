package io.github.giovanicaprison.matching.naive.boundary;

import static io.github.giovanicaprison.matching.naive.boundary.Engine.assertContinuous;
import static io.github.giovanicaprison.matching.naive.boundary.Engine.assertEvents;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What the engine refuses, and that refusing costs the book nothing. */
class ValidityTest {

  private static final String LOTS_OF_TEN =
      "INSTRUMENT tick=5 lot=10 scale=4 min=1 max=1000000 band=500 open=100000 alloc=PRICE_TIME";

  private static final String NARROW_BOUNDS =
      "INSTRUMENT tick=5 lot=1 scale=4 min=99000 max=101000 band=5000 open=100000 alloc=PRICE_TIME";

  @Test
  @DisplayName("VR-1.1 a non-positive quantity is refused")
  void nothing_is_not_a_quantity() {
    assertContinuous(
        """
        NEW      BUY LIMIT GTC 100000 0
        REJECTED #1 NON_POSITIVE_QUANTITY
        """);
  }

  @Test
  @DisplayName("VR-1.2 a quantity off the instrument's lot size is refused rather than rounded")
  void a_part_lot_is_refused() {
    assertEvents(
        LOTS_OF_TEN
            + """

            SESSION  CONTINUOUS
            STATE    CONTINUOUS

            NEW      BUY LIMIT GTC 100000 15
            REJECTED #1 LOT_VIOLATION

            NEW      BUY LIMIT GTC 100000 20
            ACCEPTED #2
            RESTED   #2 BUY 100000 20
            """);
  }

  @Test
  @DisplayName("VR-1.3 a minimum quantity above the order quantity is refused")
  void a_minimum_bigger_than_the_order_is_refused() {
    assertContinuous(
        """
        NEW      BUY LIMIT IOC 100000 50 min=60
        REJECTED #1 MINIMUM_QUANTITY_ABOVE_ORDER
        """);
  }

  @Test
  @DisplayName("VR-1.4 a display quantity above the order quantity is refused")
  void showing_more_than_you_have_is_refused() {
    assertContinuous(
        """
        NEW      BUY LIMIT GTC 100000 50 display=60
        REJECTED #1 DISPLAY_QUANTITY_ABOVE_ORDER
        """);
  }

  @Test
  @DisplayName("VR-2.1 a non-positive price on a priced order is refused")
  void nothing_is_not_a_price() {
    assertContinuous(
        """
        NEW      BUY LIMIT GTC 0 50
        REJECTED #1 NON_POSITIVE_PRICE
        """);
  }

  @Test
  @DisplayName("VR-2.2 a price off the instrument's tick size is refused rather than rounded")
  void a_price_between_ticks_is_refused() {
    assertContinuous(
        """
        NEW      BUY LIMIT GTC 100002 50
        REJECTED #1 TICK_VIOLATION
        """);
  }

  @Test
  @DisplayName("VR-2.3 a price outside the instrument's static band is refused")
  void a_price_outside_the_instrument_is_refused() {
    // On tick and inside the dynamic band, and still outside what the instrument allows at all.
    assertEvents(
        NARROW_BOUNDS
            + """

            SESSION  CONTINUOUS
            STATE    CONTINUOUS

            NEW      BUY LIMIT GTC 101005 50
            REJECTED #1 STATIC_BAND_VIOLATION
            """);
  }

  @Test
  @DisplayName("VR-2.4 a price outside the dynamic band is refused")
  void a_price_too_far_from_the_market_is_refused() {
    // On tick and inside the instrument's bounds, and too far from the reference price.
    assertContinuous(
        """
        NEW      BUY LIMIT GTC 100505 50
        REJECTED #1 DYNAMIC_BAND_VIOLATION
        """);
  }

  @Test
  @DisplayName("VR-3.1 an inconsistent field combination is refused")
  void an_order_that_contradicts_itself_is_refused() {
    // A market order told to rest until cancelled cannot do both.
    assertContinuous(
        """
        NEW      BUY MARKET GTC - 50
        REJECTED #1 INVALID_FIELDS
        """);
  }

  @Test
  @DisplayName("VR-4.1 every kind of order handles an empty book without corrupting it")
  void an_empty_book_is_not_a_special_case() {
    assertContinuous(
        """
        NEW      BUY MARKET IOC - 10
        ACCEPTED #1
        REMOVED  #1 10 IMMEDIATE_OR_CANCEL_REMAINDER

        NEW      BUY LIMIT IOC 100000 10
        ACCEPTED #2
        REMOVED  #2 10 IMMEDIATE_OR_CANCEL_REMAINDER

        NEW      BUY LIMIT FOK 100000 10
        REJECTED #3 FILL_OR_KILL_UNFILLABLE

        NEW      BUY LIMIT GTC 100000 10 POST_ONLY
        ACCEPTED #4
        RESTED   #4 BUY 100000 10

        NEW      SELL LIMIT GTC 100005 40 display=10
        ACCEPTED #5
        RESTED   #5 SELL 100005 10

        NEW      BUY MARKET IOC - 10 trigger=100500
        ACCEPTED #6

        NEW      SELL LIMIT GTC 100000 10
        ACCEPTED #7
        EXECUTED @1 aggressor=#7 resting=#4 100000 10
        """);
  }

  @Test
  @DisplayName("VR-5.1 a refusal leaves the book unchanged")
  void a_refusal_costs_the_book_nothing() {
    // Every refusal in turn, and then the book behaves exactly as it did before any of them.
    assertContinuous(
        """
        NEW      BUY LIMIT GTC 100000 50
        ACCEPTED #1
        RESTED   #1 BUY 100000 50

        NEW      SELL LIMIT GTC 100002 10
        REJECTED #2 TICK_VIOLATION

        NEW      SELL LIMIT FOK 100000 90
        REJECTED #3 FILL_OR_KILL_UNFILLABLE

        NEW      SELL LIMIT IOC 100000 90 min=80
        REJECTED #4 MINIMUM_QUANTITY_NOT_MET

        NEW      SELL LIMIT GTC 100000 10 POST_ONLY
        REJECTED #5 WOULD_CROSS

        NEW      SELL LIMIT GTC 100000 50
        ACCEPTED #6
        EXECUTED @1 aggressor=#6 resting=#1 100000 50
        """);
  }
}
