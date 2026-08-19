package com.imc.me.event.dto;

/**
 * An order's current state, as answered by the order registry (FR-5.4).
 *
 * <p>Carries the quantities and not just the {@link Status} because FR-5.4 asks for remaining
 * quantity explicitly, and because {@code Status} alone is not actionable: a client managing risk
 * needs to know <i>how much</i> is still working, not merely that something is.
 *
 * @param orderId the engine uid
 * @param status derived where derivable, recorded where not
 * @param remainingQty what is still working in the book
 * @param filledQty what has executed, so a client can reconcile its position
 */
public record OrderStatus(long orderId, Status status, long remainingQty, long filledQty) {}
