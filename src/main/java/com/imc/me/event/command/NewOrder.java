package com.imc.me.event.command;

import com.imc.me.domain.OrderSide;
import com.imc.me.domain.OrderType;

/**
 * A client's request to place an order. Inbound, immutable, unvalidated.
 *
 * <p>Kept distinct from the order entity (OOD-4): this is what a client asked for, and an {@code
 * Order} is what the engine admitted, with the validation boundary and the sequencer in between.
 * Collapsing them would make the entity constructible in an invalid state and leave the book
 * defending itself.
 *
 * @param clientOrderId the client's own reference, echoed back on every outcome (API-1.3) and never
 *     interpreted by the engine. Identity is the uid the sequencer mints (OOD-13).
 * @param side buy or sell
 * @param type the order type profile (see {@link OrderType})
 * @param qty requested quantity, validated positive and on lot
 * @param price scaled limit price (OOD-12), validated positive and on tick. Ignored for MARKET,
 *     which is given a sentinel at the boundary instead.
 */
public record NewOrder(long clientOrderId, OrderSide side, OrderType type, long qty, long price) {}
