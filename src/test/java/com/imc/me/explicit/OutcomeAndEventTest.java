package com.imc.me.explicit;

import com.imc.me.support.Requirement;
import com.imc.me.support.TestTags;
import org.junit.jupiter.api.*;

/**
 * The API contract surface i.e., typed outcomes, call safety, and that an event subscription
 * mechanism exists.
 */
@Tag(TestTags.FAST)
@DisplayName("Explicit | Outcomes & API contract")
class OutcomeAndEventTest {

  @Test
  @Requirement("API-1.1")
  @DisplayName("API-1.1: submit returns acceptance with a UID and immediate fills")
  void submit_returns_accepted() {
    // TODO: crossing submit -> Accepted(orderId, fills) with fills populated
  }

  @Test
  @Requirement("API-1.2")
  @DisplayName("API-1.2: submit returns a typed rejection with a reason when invalid")
  void submit_returns_rejected() {
    // TODO: invalid submit -> Rejected(reason)
  }

  @Test
  @Requirement("API-1.3")
  @DisplayName("API-1.3: the submit result is returned synchronously")
  void submit_is_synchronous() {
    // TODO: submit returns the outcome on the calling thread (no future/poll)
  }

  @Test
  @Requirement("API-2.1")
  @DisplayName("API-2.1: cancel distinguishes success from not-found")
  void cancel_typed_outcome() {
    // TODO: Cancelled vs NotFound are distinct types (sealed CancelResult)
  }

  @Test
  @Requirement("API-9.1")
  @DisplayName("API-9.1: errors and rejections are typed and distinguishable")
  void outcomes_are_typed() {
    // TODO: pattern-match the sealed result; each case is reachable & distinct
  }

  @Test
  @Requirement("API-10.1")
  @DisplayName("API-10.1: the contract is safe to call repeatedly")
  void safe_to_call_repeatedly() {
    // TODO: repeated submit/cancel/query calls do not corrupt state
  }

  @Test
  @Requirement("API-7.1")
  @DisplayName("API-7.1: consumers observe events without polling internal state")
  void events_observable_without_polling() {
    // TODO: register a listener, submit a crossing order, assert it was notified
  }
}
