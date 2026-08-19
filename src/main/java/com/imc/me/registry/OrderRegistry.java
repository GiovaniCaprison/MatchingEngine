package com.imc.me.registry;

import com.imc.me.domain.OrderView;
import com.imc.me.event.dto.OrderStatus;
import com.imc.me.event.dto.Status;
import java.util.HashMap;
import java.util.Map;

/**
 * Every order the engine has ever accepted, and what became of it. The only thing that can answer
 * FR-5.4.
 *
 * <p>It lives above the book rather than inside it because a filled or cancelled order leaves the
 * resting set, so status cannot be answered from the sides alone. Keeping dead orders in a side's
 * id index instead would give that index two meanings and two lifetimes (OOD-14).
 *
 * <p>Filled and remaining quantities are read through to the order rather than copied here, and
 * OPEN, PARTIALLY_FILLED and FILLED are derived rather than stored. Only terminal states that
 * cannot be derived, CANCELLED and REJECTED, are recorded. A second copy of derivable state is the
 * same mistake in miniature.
 *
 * <p>Not thread-safe: a plain {@code HashMap}, written only by the single writer (OOD-2).
 */
public final class OrderRegistry {

  /**
   * One order's registry entry. Holds a live {@link OrderView} rather than a snapshot, so
   * quantities stay current as the order fills without anything having to push updates in here.
   */
  private static final class Entry {
    private final OrderView order;
    private Status terminal;

    private Entry(final OrderView order) {
      this.order = order;
    }

    private Status status() {
      if (terminal != null) return terminal;
      if (order.remainingQty() == 0) return Status.FILLED;
      return order.filledQty() > 0 ? Status.PARTIALLY_FILLED : Status.OPEN;
    }
  }

  private final Map<Long, Entry> entries = new HashMap<>();

  /** Records an accepted order. Called once, when the boundary admits it. */
  public void accepted(final OrderView order) {
    entries.put(order.orderId(), new Entry(order));
  }

  /**
   * Records an order the boundary refused.
   *
   * <p>Rejected orders are registered too, so "I sent that, what happened?" is answerable for every
   * order a client sent rather than only the ones that reached the book. A client whose order was
   * rejected and whose query then returns unknown cannot tell a rejection from a lost message.
   */
  public void rejected(final OrderView order) {
    final Entry entry = new Entry(order);
    entry.terminal = Status.REJECTED;
    entries.put(order.orderId(), entry);
  }

  /** Marks an order cancelled, whether by the client or by its own remainder policy. */
  public void cancelled(final long orderId) {
    final Entry entry = entries.get(orderId);
    if (entry != null) entry.terminal = Status.CANCELLED;
  }

  /**
   * The current state of an order, or {@code null} if this engine has never seen that id.
   *
   * <p>{@code null} rather than a not-found status constant, so "never existed" cannot be confused
   * with a real lifecycle state. The caller turns it into a typed outcome (OOD-6).
   */
  public OrderStatus statusOf(final long orderId) {
    final Entry entry = entries.get(orderId);
    if (entry == null) return null;

    return new OrderStatus(
        orderId, entry.status(), entry.order.remainingQty(), entry.order.filledQty());
  }

  /** How many orders this engine has seen. Exists for invariant checks. */
  public int size() {
    return entries.size();
  }
}
