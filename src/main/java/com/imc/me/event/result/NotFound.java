package com.imc.me.event.result;

/**
 * No live order with that id. Ordinary rather than exceptional: it raced with a fill or a cancel.
 */
public record NotFound(long orderId) implements AmendResult, CancelResult {}
