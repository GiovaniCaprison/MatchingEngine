package com.imc.me.event.result;

public record NotFound(long orderId) implements AmendResult, CancelResult {}
