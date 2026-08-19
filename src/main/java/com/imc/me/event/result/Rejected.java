package com.imc.me.event.result;

/**
 * The order was refused and no state changed (API-8.2).
 *
 * <p>Carries both ids, because a client that pipelines submissions cannot act on a rejection
 * without knowing which order it refers to, however precise the reason (API-1.2, API-1.3).
 *
 * @param clientOrderId the client's own reference, echoed verbatim
 * @param orderId the engine uid, minted before validation so that even a refused order is
 *     addressable and appears in the registry. Without it, a client whose status query returns
 *     unknown cannot tell a rejection from a lost message.
 * @param reason machine-readable cause
 */
public record Rejected(long clientOrderId, long orderId, RejectReason reason)
    implements SubmitResult, AmendResult {}
