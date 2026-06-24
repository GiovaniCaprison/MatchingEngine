package com.imc.me;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class OrderControllerFRTests {

  @Nested
  @DisplayName("FR-1: Order Submission")
  class OrderSubmission {

    @Test
    @DisplayName("FR-1.1: Engine accepts orders that specify side, type, qty, and price")
    void orderSubmissionFR1() {}

    @Test
    @DisplayName("FR-1.2: Engine rejects orders that violate validity rules and communicates this")
    void orderSubmissionFR2() {}

    @Test
    @DisplayName("FR-1.3: Accepted orders are assigned a UID that is returned to the client")
    void orderSubmissionFR3() {}

    @Test
    @DisplayName("FR-1.4: Orders are processed in-order giving a deterministic result")
    void orderSubmissionFR4() {}
  }

  @Nested
  @DisplayName("FR-2: Order Types")
  class OrderTypes {

    @Test
    @DisplayName("FR-2.1: Limit order rests in book at its price if not fully matched")
    void orderTypeFR1() {}

    @Test
    @DisplayName("FR-2.2: Market order matches available liquidity and does not rest in book")
    void orderTypeFR2() {}

    @Test
    @DisplayName("FR-2.3: Market orders unfilled remainder is handled per cancellation policy")
    void orderTypeFR3() {}

    @Test
    @DisplayName("FR-2.4: IOC order matches available liquidity and remainder is cancelled")
    void orderTypeFR4() {}

    @Test
    @DisplayName("FR-2.5: FOK order executes in full immediately or not at all")
    void orderTypeFR5() {}

    @Test
    @DisplayName("FR-2.6: Post order never crosses the spread and rejects or reprices instead")
    void orderTypeFR6() {}
  }

  @Nested
  @DisplayName("FR-3: Matching")
  class Matching {

    @Test
    @DisplayName("FR-3.1: Matching follows price priority where best price is filled first")
    void matchingFR1() {}

    @Test
    @DisplayName("FR-3.2: Matching follows time priority at equal prices with FIFO")
    void matchingFR2() {}

    @Test
    @DisplayName("FR-3.3: An agressing order is matched against available resting liquidity")
    void matchingFR3() {}

    @Test
    @DisplayName("FR-3.4: Trade record is produced for every match with orders, qty, and price")
    void matchingFR4() {}

    @Test
    @DisplayName("FR-3.5: Price improvement accrues to aggressor and is that of the resting order")
    void matchingFR5() {}
  }

  @Nested
  @DisplayName("FR-4: Cancellation & Amendment")
  class CancellationAndAmendment {

    @Test
    @DisplayName("FR-4.1: Engine allows cancellation of a resting order by its UID")
    void cancellationAndAmendmentFR1() {}

    @Test
    @DisplayName("FR-4.2: Cancellation is idempotent and explicitly fails for re-cancelled orders")
    void cancellationAndAmendmentFR2() {}

    @Test
    @DisplayName("FR-4.3: Engine allows amendment of resting orders qty and or price")
    void cancellationAndAmendmentFR3() {}

    @Test
    @DisplayName("FR-4.4: Amendment which increases qty or changes price looses time-priority")
    void cancellationAndAmendmentFR4() {}

    @Test
    @DisplayName("FR-4.5: Amendment which only decreases qty does not loose time-priority")
    void cancellationAndAmendmentFR5() {}
  }

  @Nested
  @DisplayName("FR-5: Querying")
  class Querying {

    @Test
    @DisplayName("FR-5.1: Engine exposes current best bid and ask with price and aggregate qty")
    void queryingFR1() {}

    @Test
    @DisplayName("FR-5.2: Engine clearly indicates when a side is empty")
    void queryingFR2() {}

    @Test
    @DisplayName("FR-5.3: Engine aggregates depth for side, price, and resting qty by priority")
    void queryingFR3() {}

    @Test
    @DisplayName("FR-5.4: Engine retrieves current state of an order by UID incl remaining qty")
    void queryingFR4() {}

    @Test
    @DisplayName("FR-5.5: All query operations are read-only")
    void queryingFR5() {}
  }

  @Nested
  @DisplayName("FR-6: Events & Outputs")
  class EventsAndOutputs {

    @Test
    @DisplayName("FR-6.1: Engine emits acceptance, fills, placement, and terminal state")
    void eventsAndOutputsFR1() {}

    @Test
    @DisplayName("FR-6.2: Events let a consumer compute positions and trade history")
    void eventsAndOutputsFR2() {}
  }
}
