package com.imc.me.domain;

/**
 * One execution: the engine's real output (FR-3.4, FR-6.1).
 *
 * <p>{@code sequence} is its position in the engine's total order, minted by the sequencer (OOD-13).
 * Without it, "identical input produces identical trades" (NFR-1.1) can only be checked by comparing
 * whole collections positionally, and an audit trail cannot be reconstructed at all — you would have
 * no way to say which of two executions at the same price happened first.
 *
 * <p>{@code price} is the <b>resting</b> order's price, because price improvement accrues to the
 * aggressor (FR-3.5). Both ids are engine uids, not client order ids.
 */
public record Trade(long sequence, long aggressorId, long restingId, long price, long qty) {}
