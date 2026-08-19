package com.imc.me.domain;

/**
 * One execution, and the engine's real output (FR-3.4, FR-6.1).
 *
 * <p>{@code sequence} is the execution's position in the engine's total order, minted by the
 * sequencer (OOD-13). It is what allows NFR-1.1 to be checked by comparing values rather than whole
 * collections positionally, and what orders an audit trail when two executions share a price.
 *
 * <p>{@code price} is the resting order's price, since price improvement accrues to the aggressor
 * (FR-3.5). Both ids are engine uids rather than client order ids.
 */
public record Trade(long sequence, long aggressorId, long restingId, long price, long qty) {}
