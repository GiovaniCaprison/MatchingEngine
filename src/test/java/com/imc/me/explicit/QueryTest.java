package com.imc.me.explicit;

import com.imc.me.support.Requirement;
import com.imc.me.support.TestTags;
import org.junit.jupiter.api.*;

/** Read-side queries with small, exact outputs. */
@Tag(TestTags.FAST)
@DisplayName("Explicit | Queries")
class QueryTest {

  @Test
  @Requirement("FR-5.1")
  @DisplayName("FR-5.1: best bid/ask exposes price and aggregate qty")
  void top_of_book_price_and_qty() {
    // TODO: rest two orders one side -> topOfBook(side) = price + summed qty
  }

  @Test
  @Requirement("FR-5.2")
  @DisplayName("FR-5.2: an empty side is clearly indicated")
  void empty_side_indicated() {
    // TODO: topOfBook(emptySide).isEmpty() == true (no null, no sentinel leak)
  }

  @Test
  @Requirement("FR-5.4")
  @DisplayName("FR-5.4: order state by UID includes remaining qty")
  void order_status_by_uid() {
    // TODO: partially fill a resting order -> status(uid).remainingQty correct
  }

  @Test
  @Requirement("API-4.1")
  @DisplayName("API-4.1: a top-of-book query is exposed")
  void top_of_book_exposed() {
    // TODO: the public TopOfBook query exists and returns per-side
  }

  @Test
  @Requirement("API-5.1")
  @DisplayName("API-5.1: a depth query returns aggregated levels per side")
  void depth_exposed() {
    // TODO: depth(side) returns ordered levels with aggregate qty
  }

  @Test
  @Requirement("API-6.1")
  @DisplayName("API-6.1: an order-status query by UID is exposed")
  void status_exposed() {
    // TODO: status(uid) returns a typed status (incl. not-found case)
  }
}
