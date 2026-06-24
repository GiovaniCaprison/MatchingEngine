package com.imc.svc.model;

/**
 * The side of the order. Clients can either buy or sell.
 *
 * <ul>
 *   <li>Buy order - client wishes to buy (long) the instrument represented by this order
 *   <li>Sell order - client wishes to sell (short) the instrument represented by this order
 * </ul>
 */
public enum OrderSide {
  BUY,
  SELL
}
