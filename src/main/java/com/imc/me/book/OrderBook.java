package com.imc.me.book;

/**
 * A book: readable and writable.
 *
 * <p>Deliberately <b>not</b> {@code sealed} (OOD-7). Sealing buys exactly one thing — exhaustiveness
 * checking in a {@code switch} — and nobody will ever switch over book implementations; that would
 * be a type test on the data structure, which is the thing the interface exists to avoid. Meanwhile
 * {@code ENGINEERING_GUIDE.md} plans an {@code ArrayOrderBook}, so a {@code permits} clause is a
 * guaranteed future three-file edit for no benefit, while falsely signalling that the set of
 * implementations is closed.
 *
 * <p>The read/write split it extends is worth keeping for a different reason: it is capability
 * narrowing (OOD-17). A market-data consumer can be handed an {@link OrderBookReader} and be
 * <i>unable</i> to mutate the book, enforced by the type rather than by convention.
 */
public interface OrderBook extends OrderBookReader, OrderBookWriter {}
