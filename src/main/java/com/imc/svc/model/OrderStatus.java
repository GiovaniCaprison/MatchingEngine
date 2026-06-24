package svc.model;

/**
 * The status of the order. Each order will be one of these.
 *
 * <ul>
 *   <li>Open - the order is not yet filled and is waiting to be matched.
 *   <li>Filled - the order has been filled meaning it has been completed as requested.
 *   <li>Cancelled - the order has been cancelled either before or after partially being filled.
 * </ul>
 */
public enum OrderStatus {
  OPEN,
  FILLED,
  CANCELLED
}
