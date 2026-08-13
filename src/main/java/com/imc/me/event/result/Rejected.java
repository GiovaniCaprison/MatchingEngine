package com.imc.me.event.result;

/**
 * The order was refused and no state changed (API-8.2).
 *
 * <p>Carries <b>both</b> ids, which the original version of this record did not. Without them a client
 * that pipelines submissions cannot tell <i>which</i> order was rejected, so the rejection is
 * unactionable however precise its reason (API-1.2, API-1.3).
 *
 * @param clientOrderId the client's own reference, echoed verbatim
 * @param orderId the engine uid, minted before validation so that even a refused order is
 *     addressable and appears in the registry -- a client whose query returns "unknown" cannot
 *     otherwise tell a rejection from a lost message
 * @param reason machine-readable cause
 */
public record Rejected(long clientOrderId, long orderId, RejectReason reason)
    implements SubmitResult, AmendResult {}
