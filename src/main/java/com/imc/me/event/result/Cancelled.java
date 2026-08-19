package com.imc.me.event.result;

/**
 * The order was found resting and removed.
 *
 * <p>It used to carry the fills it had accumulated before being cancelled, and that field was
 * always empty, because nothing keeps a per-order trade list. Adding one would mean a growable
 * collection per order, allocated on the write path whether or not anyone ever cancels, which is
 * the allocation budget gone for a field most clients would not read (OOD-11).
 *
 * <p>A client that wants the fills already has two ways to get them: {@link
 * com.imc.me.MatchingEngine#status} gives the filled and remaining quantities, and the event stream
 * gives the individual executions with their sequence numbers. Both are authoritative, and neither
 * costs anything on the hot path.
 */
public record Cancelled(long orderId) implements CancelResult {}
