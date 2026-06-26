package com.imc.me.explicit;

import com.imc.me.support.Requirement;
import com.imc.me.support.TestTags;
import org.junit.jupiter.api.*;

/**
 * Atomic, small-output, one requirement per test, intent-revealing assertions. These are the "yes,
 * this does this, full-stop" tests. They prove existence.
 */
@Tag(TestTags.FAST)
@DisplayName("Explicit | Order lifecycle")
class OrderLifecycleTest {

  @Nested
  @DisplayName("FR-1: Order Submission")
  class OrderSubmission {

    @Test
    @Requirement("FR-1.1")
    @DisplayName("FR-1.1: accepts an order specifying side, type, qty, and price")
    void accepts_well_formed_order() {
      // TODO: submit a valid limit order -> assertThat(result).isInstanceOf(Accepted.class)
    }

    @Test
    @Requirement("FR-1.2")
    @DisplayName("FR-1.2: rejects an invalid order and communicates the reason")
    void rejects_invalid_order_with_reason() {
      // TODO: submit invalid order -> Rejected with a non-null typed RejectReason
    }

    @Test
    @Requirement("FR-1.3")
    @DisplayName("FR-1.3: an accepted order carries a UID returned to the client")
    void accepted_order_returns_uid() {
      // TODO: Accepted.orderId() is present/positive and unique across two submits
    }
  }

  @Nested
  @DisplayName("FR-2: Resting Behaviour")
  class RestingBehaviour {

    @Test
    @Requirement("FR-2.1")
    @DisplayName("FR-2.1: an unmatched limit order rests at its price")
    void unmatched_limit_rests() {
      // TODO: submit limit into empty book -> topOfBook(side) shows it
    }

    @Test
    @Requirement("FR-2.2")
    @DisplayName("FR-2.2: a fully-unmatched market order does not rest")
    void unfilled_market_does_not_rest() {
      // TODO: market into empty book -> book unchanged, no resting order
    }
  }

  @Nested
  @DisplayName("FR-4: Cancellation & amendment")
  class CancelAmend {

    @Test
    @Requirement("FR-4.1")
    @DisplayName("FR-4.1: a resting order can be cancelled by its UID")
    void cancel_resting_by_uid() {
      // TODO: rest an order, cancel(uid) -> Cancelled, and it leaves the book
    }

    @Test
    @Requirement("FR-4.2")
    @DisplayName("FR-4.2: cancel is idempotent and re-cancel fails explicitly with \"not-found\"")
    void recancel_returns_not_found() {
      // TODO: cancel(uid) twice -> first Cancelled, second NotFound (never throws)
    }

    @Test
    @Requirement("FR-4.3")
    @DisplayName("FR-4.3: a resting order's qty and/or price can be amended")
    void amend_applies() {
      // TODO: amend qty down -> status(uid).remainingQty reflects the new value
    }
  }
}
