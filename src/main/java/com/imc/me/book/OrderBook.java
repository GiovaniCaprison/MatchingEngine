package com.imc.me.book;

/**
 * A book: readable and writable.
 *
 * <p>Not sealed, on purpose (OOD-7). Nobody will switch over book implementations, an {@code
 * ArrayOrderBook} is planned, and a {@code permits} clause would be a guaranteed future edit in
 * another file for nothing.
 *
 * <p>The read and write split it extends stays for a different reason: capability narrowing
 * (OOD-17). A market data consumer can be handed an {@link OrderBookReader} and be unable to mutate
 * the book, enforced by the type rather than by convention.
 */
public interface OrderBook extends OrderBookReader, OrderBookWriter {}
