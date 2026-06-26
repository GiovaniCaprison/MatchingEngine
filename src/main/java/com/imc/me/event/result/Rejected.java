package com.imc.me.event.result;

public record Rejected(RejectReason reason) implements SubmitResult, AmendResult {}
