package com.imc.me.explicit;

import static org.assertj.core.api.Assertions.assertThat;

import com.imc.me.MatchingEngine;
import com.imc.me.domain.Instrument;
import com.imc.me.domain.OrderSide;
import com.imc.me.domain.OrderType;
import com.imc.me.event.command.NewOrder;
import com.imc.me.event.dto.Status;
import com.imc.me.event.result.Accepted;
import com.imc.me.event.result.RejectReason;
import com.imc.me.event.result.Rejected;
import com.imc.me.event.result.SubmitOutcome;
import com.imc.me.event.result.SubmitResult;
import com.imc.me.event.sink.EngineListener;
import com.imc.me.support.NoCrossMatcher;
import com.imc.me.support.Requirement;
import com.imc.me.support.TestTags;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The validation boundary: invalid orders are refused with an actionable reason, and refusal changes
 * nothing (VR-1.1, VR-2.1, VR-2.2, VR-3.2, API-8.1, API-8.2).
 */
@Tag(TestTags.FAST)
@DisplayName("Explicit | Boundary validation")
class BoundaryValidationTest {

  /** Tick 25, lot 5 — so off-tick and off-lot are both reachable with small numbers. */
  private static final Instrument INSTRUMENT = new Instrument(1, "TEST", 25L, 5L, 4);

  /**
   * A matcher that never crosses, so these tests measure the boundary rather than the algorithm.
   * Everything asserted here is true regardless of how matching is implemented.
   */
  private final MatchingEngine engine = new MatchingEngine(INSTRUMENT, new NoCrossMatcher());

  private static NewOrder order(final long qty, final long price) {
    return new NewOrder(7000L, OrderSide.BUY, OrderType.LIMIT, qty, price);
  }

  private RejectReason rejectionFor(final NewOrder command) {
    final SubmitResult result = engine.submit(command);
    assertThat(result).isInstanceOf(Rejected.class);
    return ((Rejected) result).reason();
  }

  @Test
  @Requirement("VR-1.1")
  @DisplayName("VR-1.1: zero and negative quantities are rejected")
  void non_positive_qty_is_rejected() {
    // Zero is not a small order, it is a meaningless one.
    assertThat(rejectionFor(order(0L, 100L))).isEqualTo(RejectReason.NON_POSITIVE_QTY);
    assertThat(rejectionFor(order(-5L, 100L))).isEqualTo(RejectReason.NON_POSITIVE_QTY);
  }

  @Test
  @Requirement("VR-2.1")
  @DisplayName("VR-2.1: zero and negative prices are rejected")
  void non_positive_price_is_rejected() {
    assertThat(rejectionFor(order(5L, 0L))).isEqualTo(RejectReason.NON_POSITIVE_PRICE);
    assertThat(rejectionFor(order(5L, -25L))).isEqualTo(RejectReason.NON_POSITIVE_PRICE);
  }

  @Test
  @Requirement("VR-2.2")
  @DisplayName("VR-2.2: an off-tick price is rejected rather than rounded")
  void off_tick_price_is_rejected() {
    // Rounding would trade at a price the client never asked for. Off-tick and over-precision are the
    // same check once prices are scaled longs -- a quiet payoff of never using a double (OOD-12).
    assertThat(rejectionFor(order(5L, 110L))).isEqualTo(RejectReason.TICK_VIOLATION);
    assertThat(engine.submit(order(5L, 125L))).isInstanceOf(Accepted.class);
  }

  @Test
  @Requirement("VR-1.1")
  @DisplayName("VR-1.1: an off-lot quantity is rejected because it could not settle")
  void off_lot_qty_is_rejected() {
    assertThat(rejectionFor(order(7L, 100L))).isEqualTo(RejectReason.LOT_VIOLATION);
    assertThat(engine.submit(order(10L, 100L))).isInstanceOf(Accepted.class);
  }

  @Test
  @Requirement("VR-3.2")
  @DisplayName("VR-3.2: a malformed order type or side is rejected, not dereferenced")
  void malformed_type_is_rejected() {
    // A missing enum means a bad wire decode. Refusing it here beats an NPE somewhere less obvious.
    assertThat(rejectionFor(new NewOrder(1L, OrderSide.BUY, null, 5L, 100L)))
        .isEqualTo(RejectReason.UNKNOWN_ORDER_TYPE);
    assertThat(rejectionFor(new NewOrder(1L, null, OrderType.LIMIT, 5L, 100L)))
        .isEqualTo(RejectReason.UNKNOWN_ORDER_TYPE);
  }

  @Test
  @Requirement("API-8.2")
  @DisplayName("API-8.2: a rejection leaves the book exactly as it was")
  void rejection_does_not_modify_the_book() {
    engine.submit(order(10L, 100L));
    final long restingQty = engine.topOfBook(OrderSide.BUY).qty();
    final int levels = engine.depth(OrderSide.BUY, 10).levels().size();

    engine.submit(order(0L, 100L));
    engine.submit(order(5L, 110L));
    engine.submit(order(7L, 100L));

    // True by construction rather than by cleanup: validation runs strictly before anything is
    // touched, so there is no partial mutation to undo (OOD-5).
    assertThat(engine.topOfBook(OrderSide.BUY).qty()).isEqualTo(restingQty);
    assertThat(engine.depth(OrderSide.BUY, 10).levels().size()).isEqualTo(levels);
  }

  @Test
  @Requirement("API-1.2")
  @DisplayName("API-1.2: a rejection carries both ids so a pipelining client can correlate it")
  void rejection_carries_identity() {
    final Rejected rejected = (Rejected) engine.submit(new NewOrder(4242L, OrderSide.BUY, OrderType.LIMIT, 0L, 100L));

    // Without these a client that pipelines submissions cannot tell WHICH order was refused, so the
    // rejection is unactionable however precise its reason.
    assertThat(rejected.clientOrderId()).isEqualTo(4242L);
    assertThat(rejected.orderId()).isPositive();
  }

  @Test
  @Requirement("API-1.3")
  @DisplayName("API-1.3: the client order id is echoed on acceptance and never interpreted")
  void client_order_id_is_echoed() {
    final Accepted accepted = (Accepted) engine.submit(new NewOrder(99L, OrderSide.BUY, OrderType.LIMIT, 5L, 100L));

    assertThat(accepted.clientOrderId()).isEqualTo(99L);
    assertThat(accepted.outcome()).isEqualTo(SubmitOutcome.RESTED);
  }

  @Test
  @Requirement("FR-1.3")
  @DisplayName("FR-1.3: every accepted order gets a unique engine uid")
  void accepted_orders_get_unique_uids() {
    final Accepted first = (Accepted) engine.submit(order(5L, 100L));
    final Accepted second = (Accepted) engine.submit(order(5L, 100L));

    assertThat(first.orderId()).isNotEqualTo(second.orderId());
    assertThat(second.orderId()).isGreaterThan(first.orderId());
  }

  @Test
  @Requirement("FR-5.4")
  @DisplayName("FR-5.4: a rejected order is still queryable, so it cannot look like a lost message")
  void rejected_order_is_queryable() {
    final Rejected rejected = (Rejected) engine.submit(order(0L, 100L));

    assertThat(engine.status(rejected.orderId())).isPresent();
    assertThat(engine.status(rejected.orderId()).orElseThrow().status()).isEqualTo(Status.REJECTED);
    assertThat(engine.status(123456L)).isEmpty();
  }

  @Test
  @Requirement("API-7.1")
  @DisplayName("API-7.1: a registered consumer receives acceptances and rejections")
  void listener_receives_events() {
    final List<String> events = new ArrayList<>();
    engine.register(
        new EngineListener() {
          @Override
          public void onAccepted(final long clientOrderId, final long orderId) {
            events.add("accepted:" + clientOrderId);
          }

          @Override
          public void onRejected(
              final long clientOrderId, final long orderId, final RejectReason reason) {
            events.add("rejected:" + clientOrderId + ":" + reason);
          }
        });

    engine.submit(new NewOrder(1L, OrderSide.BUY, OrderType.LIMIT, 5L, 100L));
    engine.submit(new NewOrder(2L, OrderSide.BUY, OrderType.LIMIT, 0L, 100L));

    // Default methods on the listener mean a consumer implements only what it cares about -- this one
    // never mentions trades or terminal states.
    assertThat(events).containsExactly("accepted:1", "rejected:2:NON_POSITIVE_QTY");
  }

  @Test
  @Requirement("API-2.1")
  @DisplayName("API-2.1: cancel is idempotent — the second one is not-found, not an error")
  void cancel_is_idempotent() {
    final Accepted accepted = (Accepted) engine.submit(order(5L, 100L));

    assertThat(engine.cancel(accepted.orderId()))
        .isInstanceOf(com.imc.me.event.result.Cancelled.class);
    assertThat(engine.cancel(accepted.orderId()))
        .isInstanceOf(com.imc.me.event.result.NotFound.class);
    assertThat(engine.status(accepted.orderId()).orElseThrow().status())
        .isEqualTo(Status.CANCELLED);
  }
}
