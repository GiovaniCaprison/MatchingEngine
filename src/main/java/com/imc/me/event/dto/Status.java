package com.imc.me.event.dto;

/**
 * The lifecycle state of an order.
 *
 * <p>OPEN, PARTIALLY_FILLED and FILLED are derived from the order's quantities rather than stored,
 * so they cannot drift out of step with them (OOD-14). CANCELLED and REJECTED are recorded, because
 * nothing about the quantities distinguishes "cancelled with 4 left" from "still working with 4
 * left".
 *
 * <ul>
 *   <li>Open: accepted, nothing executed, still working.
 *   <li>Partially filled: some quantity executed, the rest still working. Separate from OPEN
 *       because a client that sees a partial fill has a position to manage.
 *   <li>Filled: fully executed. Terminal.
 *   <li>Cancelled: withdrawn before completing, by the client or by its own remainder policy.
 *       Terminal.
 *   <li>Rejected: refused at the validation boundary and never entered the book. Terminal, and
 *       registered rather than forgotten so a client cannot mistake a rejection for a lost message.
 * </ul>
 */
public enum Status {
  OPEN,
  PARTIALLY_FILLED,
  FILLED,
  CANCELLED,
  REJECTED
}
