package com.imc.me.event.command;

import com.imc.me.domain.OrderSide;
import com.imc.me.domain.OrderType;

/**
 * A client's request to place an order. Inbound, immutable, unvalidated.
 *
 * <p>Distinct from the order entity on purpose (OOD-4): this is what a client <i>asked for</i>, and an
 * {@code Order} is what the engine <i>admitted</i>. Between them sits the validation boundary and the
 * sequencer. Collapsing the two would mean an unvalidated, un-sequenced client request could be handed
 * straight to the book — the entity would have to be constructible in an invalid state, and the book
 * would have to defend itself, which is the whole thing OOD-5 avoids.
 *
 * @param clientOrderId the client's own reference, echoed back on every outcome (API-1.3) and never
 *     interpreted by the engine. Not unique from the engine's point of view; identity is the uid the
 *     sequencer mints (OOD-13).
 * @param side buy or sell
 * @param type the order type profile (see {@link OrderType})
 * @param qty requested quantity, validated positive and on lot
 * @param price scaled limit price (OOD-12), validated positive and on tick. Ignored for MARKET, which
 *     is given a sentinel at the boundary instead.
 */
public record NewOrder(
    long clientOrderId, OrderSide side, OrderType type, long qty, long price) {}
