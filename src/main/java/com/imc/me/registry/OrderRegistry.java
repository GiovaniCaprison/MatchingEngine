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
 * <p><b>Why this is not part of the book.</b> A filled or cancelled order <i>leaves</i> the resting
 * set, so status cannot be answered from the book's sides alone. The tempting fix — keep dead orders
 * in the side's id index — is exactly the bug OOD-14 exists to prevent: that index would acquire two
 * meanings ("resting here" and "known to the system") with two lifetimes, and every subsequent reader
 * would have to guess which one it was looking at. The book stays a book; this lives above it.
 *
 * <table border="1">
 *   <caption>Who owns what</caption>
 *   <tr><th>State</th><th>Owner</th><th>Lifetime</th></tr>
 *   <tr><td>which orders are resting, and where</td><td>{@code BookSide.ordersById}</td><td>while resting</td></tr>
 *   <tr><td>every order ever accepted, and its terminal state</td><td>this</td><td>session</td></tr>
 * </table>
 *
 * <p><b>What it deliberately does not store.</b> Filled and remaining quantities are <i>read through</i>
 * to the order rather than copied here, and OPEN/PARTIALLY_FILLED/FILLED are derived rather than
 * recorded. Only genuinely terminal states that cannot be derived — CANCELLED, REJECTED — are stored.
 * Copying derivable state would create a second copy that can disagree with the first, which is the
 * same mistake in miniature (OOD-14).
 *
 * <p>Not thread-safe: a plain {@code HashMap}, written only by the single writer (OOD-2).
 */
public final class OrderRegistry {

  /**
   * One order's registry entry.
   *
   * <p>Holds a live {@link OrderView}, not a snapshot, so quantities stay current as the order fills
   * without anything having to push updates in here. {@code terminal} is the only recorded state.
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
   * <p>Rejected orders are registered too, so that "I sent that, what happened?" is answerable for
   * every order a client sent — not only the ones that made it into the book. A client whose order was
   * rejected and whose query then returns "unknown" cannot tell a rejection from a lost message.
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
   * <p>{@code null} rather than a "not found" status constant so that "never existed" cannot be
   * confused with a real lifecycle state; the caller turns it into a typed outcome (OOD-6).
   */
  public OrderStatus statusOf(final long orderId) {
    final Entry entry = entries.get(orderId);
    if (entry == null) return null;

    return new OrderStatus(
        orderId, entry.status(), entry.order.remainingQty(), entry.order.filledQty());
  }

  /** How many orders this engine has seen. Exists for invariant checks (NFR-6.2). */
  public int size() {
    return entries.size();
  }
}
