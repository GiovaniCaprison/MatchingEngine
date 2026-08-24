package io.github.giovanicaprison.matching.naive.boundary;

import static io.github.giovanicaprison.matching.naive.boundary.Engine.assertContinuous;
import static io.github.giovanicaprison.matching.naive.boundary.Engine.assertEvents;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Configuration and order entry. */
class OrderEntryTest {

  @Test
  @DisplayName("FR-1.1 the definition command configures the engine and precedes everything")
  void the_instrument_is_configured_by_command() {
    // A price legal on a five tick and illegal on a twenty five tick, refused because the
    // definition said twenty five. Nothing but the command could have told the engine that.
    assertEvents(
        """
        INSTRUMENT tick=25 lot=1 scale=4 min=1 max=1000000 band=500 open=100000 alloc=PRICE_TIME
        SESSION  CONTINUOUS
        STATE    CONTINUOUS
        NEW      BUY LIMIT GTC 100010 50
        REJECTED #1 TICK_VIOLATION
        NEW      BUY LIMIT GTC 100025 50
        ACCEPTED #2
        RESTED   #2 BUY 100025 50
        """);
  }

  @Test
  @DisplayName("FR-1.2 an order using every field the protocol defines for it is accepted")
  void every_field_is_read() {
    assertContinuous(
        """
        NEW      BUY LIMIT GTC 100000 100 display=40 smp=7 p=3
        ACCEPTED #1
        RESTED   #1 BUY 100000 40

        NEW      SELL LIMIT IOC 99995 60 min=20 smp=9 p=4
        ACCEPTED #2
        EXECUTED @1 aggressor=#2 resting=#1 100000 40
        RESTED   #1 BUY 100000 40
        EXECUTED @2 aggressor=#2 resting=#1 100000 20
        """);
  }

  @Test
  @DisplayName("FR-1.3 an accepted order is assigned an id of its own and reported")
  void every_order_gets_its_own_id() {
    // Two orders that both rest. If they shared an id the second rest would name the first order.
    assertContinuous(
        """
        NEW      BUY LIMIT GTC 100000 10
        ACCEPTED #1
        RESTED   #1 BUY 100000 10

        NEW      BUY LIMIT GTC 99995 20
        ACCEPTED #2
        RESTED   #2 BUY 99995 20
        """);
  }

  @Test
  @DisplayName("FR-1.4 a refused order is reported with a reason and changes nothing")
  void a_refusal_changes_nothing() {
    assertContinuous(
        """
        NEW      BUY LIMIT GTC 100002 50
        REJECTED #1 TICK_VIOLATION

        NEW      SELL MARKET IOC - 50
        ACCEPTED #2
        REMOVED  #2 50 IMMEDIATE_OR_CANCEL_REMAINDER
        """);
  }
}
