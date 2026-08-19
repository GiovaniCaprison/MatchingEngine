package com.imc.me.event.dto;

/**
 * An order's current state, as answered by the order registry (FR-5.4).
 *
 * <p>Carries the quantities rather than only the {@link Status}, because a client managing risk
 * needs to know how much is still working and not merely that something is.
 *
 * @param orderId the engine uid
 * @param status derived where derivable, recorded where not
 * @param remainingQty what is still working in the book
 * @param filledQty what has executed, so a client can reconcile its position
 */
public record OrderStatus(long orderId, Status status, long remainingQty, long filledQty) {}
