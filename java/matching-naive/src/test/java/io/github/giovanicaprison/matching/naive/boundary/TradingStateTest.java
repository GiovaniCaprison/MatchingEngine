package io.github.giovanicaprison.matching.naive.boundary;

import static io.github.giovanicaprison.matching.naive.boundary.Engine.assertBeforeOpen;
import static io.github.giovanicaprison.matching.naive.boundary.Engine.assertEvents;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What the trading state decides, and what moves it. */
class TradingStateTest {

  @Test
  @DisplayName("FR-7.9 an engine is in pre-open until a state command arrives, so nothing matches")
  void an_engine_starts_before_the_open() {
    // Two orders that cross, and no execution. Nothing but a command could open the market.
    assertBeforeOpen(
        """
        NEW      BUY LIMIT GTC 100000 50
        ACCEPTED #1
        RESTED   #1 BUY 100000 50

        NEW      SELL LIMIT GTC 100000 50
        ACCEPTED #2
        RESTED   #2 SELL 100000 50
        """);
  }

  @Test
  @DisplayName("FR-7.1 the trading state changes on a command and on nothing else")
  void only_a_command_moves_the_state() {
    // A halt holds through everything that follows it, and the market reopens when a command says
    // so and not before.
    assertBeforeOpen(
        """
        SESSION  HALTED
        STATE    HALTED

        NEW      BUY LIMIT GTC 100000 50
        ACCEPTED #1
        RESTED   #1 BUY 100000 50

        NEW      SELL LIMIT GTC 100000 20
        ACCEPTED #2
        RESTED   #2 SELL 100000 20

        SESSION  CONTINUOUS
        STATE    CONTINUOUS

        NEW      SELL LIMIT GTC 100000 20
        ACCEPTED #3
        EXECUTED @1 aggressor=#3 resting=#1 100000 20
        """);
  }

  @Test
  @DisplayName("FR-7.2 the states are pre-open, the two auctions, continuous, halted and closed")
  void every_state_can_be_entered() {
    assertBeforeOpen(
        """
        SESSION PRE_OPEN
        STATE   PRE_OPEN
        SESSION OPENING_AUCTION
        STATE   OPENING_AUCTION
        SESSION CONTINUOUS
        STATE   CONTINUOUS
        SESSION CLOSING_AUCTION
        STATE   CLOSING_AUCTION
        SESSION HALTED
        STATE   HALTED
        SESSION CLOSED
        STATE   CLOSED
        """);
  }

  @Test
  @DisplayName("FR-7.3 entry, replacement and cancellation are legal in every state except closed")
  void closed_is_the_only_state_that_refuses() {
    assertBeforeOpen(
        """
        SESSION  HALTED
        STATE    HALTED

        NEW      BUY LIMIT GTC 100000 50
        ACCEPTED #1
        RESTED   #1 BUY 100000 50

        REPLACE  #1 40 100000
        REDUCED  #1 40

        CANCEL   #1
        REMOVED  #1 40 CANCELLED

        NEW      BUY LIMIT GTC 100000 50
        ACCEPTED #2
        RESTED   #2 BUY 100000 50

        SESSION  CLOSED
        STATE    CLOSED

        NEW      BUY LIMIT GTC 100000 10
        REJECTED #3 STATE_NOT_PERMITTED

        REPLACE  #2 10 100000
        REJECTED #2 STATE_NOT_PERMITTED

        CANCEL   #2
        REJECTED #2 STATE_NOT_PERMITTED
        """);
  }

  @Test
  @DisplayName("FR-7.4 continuous matching happens only in the continuous state")
  void matching_waits_for_continuous() {
    assertBeforeOpen(
        """
        SESSION  PRE_OPEN
        STATE    PRE_OPEN

        NEW      BUY LIMIT GTC 100000 50
        ACCEPTED #1
        RESTED   #1 BUY 100000 50

        NEW      SELL LIMIT GTC 99995 50
        ACCEPTED #2
        RESTED   #2 SELL 99995 50

        SESSION  CONTINUOUS
        STATE    CONTINUOUS

        NEW      SELL LIMIT GTC 99995 10
        ACCEPTED #3
        EXECUTED @1 aggressor=#3 resting=#1 100000 10
        """);
  }

  @Test
  @DisplayName("FR-7.5 an auction uncrosses at the price that trades the most")
  void the_uncrossing_price_maximises_volume() {
    // Forty trades at a hundred thousand and thirty at a hundred thousand and ten, so the lower
    // price
    // wins on volume even though the higher one has a bid sitting on it.
    assertBeforeOpen(
        """
        SESSION    OPENING_AUCTION
        STATE      OPENING_AUCTION

        NEW        BUY LIMIT GTC 100010 30
        ACCEPTED   #1
        RESTED     #1 BUY 100010 30

        NEW        BUY LIMIT GTC 100000 20
        ACCEPTED   #2
        RESTED     #2 BUY 100000 20

        NEW        SELL LIMIT GTC 100000 40
        ACCEPTED   #3
        RESTED     #3 SELL 100000 40
        INDICATIVE 100000 40

        SESSION    CONTINUOUS
        EXECUTED   @1 aggressor=#1 resting=#3 100000 30
        EXECUTED   @2 aggressor=#2 resting=#3 100000 10
        STATE      CONTINUOUS
        """);
  }

  @Test
  @DisplayName("FR-7.6 an auction executes all matched quantity at one price")
  void an_auction_trades_at_a_single_price() {
    assertBeforeOpen(
        """
        SESSION    OPENING_AUCTION
        STATE      OPENING_AUCTION

        NEW        BUY LIMIT GTC 100000 20
        ACCEPTED   #1
        RESTED     #1 BUY 100000 20

        NEW        BUY LIMIT GTC 100000 10
        ACCEPTED   #2
        RESTED     #2 BUY 100000 10

        NEW        SELL LIMIT GTC 100000 30
        ACCEPTED   #3
        RESTED     #3 SELL 100000 30
        INDICATIVE 100000 30

        SESSION    CONTINUOUS
        EXECUTED   @1 aggressor=#1 resting=#3 100000 20
        EXECUTED   @2 aggressor=#2 resting=#3 100000 10
        STATE      CONTINUOUS
        """);
  }

  @Test
  @DisplayName("FR-7.10 an auction uncrosses on the way out of the auction state")
  void the_uncrossing_happens_on_leaving() {
    // Leaving is what runs it, whatever comes next: this one closes rather than opening, and the
    // executions are reported before the state that follows them.
    assertBeforeOpen(
        """
        SESSION    CLOSING_AUCTION
        STATE      CLOSING_AUCTION

        NEW        BUY LIMIT GTC 100000 20
        ACCEPTED   #1
        RESTED     #1 BUY 100000 20

        NEW        SELL LIMIT GTC 100000 20
        ACCEPTED   #2
        RESTED     #2 SELL 100000 20
        INDICATIVE 100000 20

        SESSION    CLOSED
        EXECUTED   @1 aggressor=#1 resting=#2 100000 20
        STATE      CLOSED
        """);
  }

  @Test
  @DisplayName("FR-7.8 a halt cancels nothing and the book is intact on resumption")
  void a_halt_keeps_the_book() {
    assertEvents(
        Engine.INSTRUMENT
            + """

            SESSION  CONTINUOUS
            STATE    CONTINUOUS

            NEW      BUY LIMIT GTC 100000 50
            ACCEPTED #1
            RESTED   #1 BUY 100000 50

            SESSION  HALTED
            STATE    HALTED

            SESSION  CONTINUOUS
            STATE    CONTINUOUS

            NEW      SELL LIMIT GTC 100000 50
            ACCEPTED #2
            EXECUTED @1 aggressor=#2 resting=#1 100000 50
            """);
  }
}
