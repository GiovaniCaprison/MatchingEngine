package com.imc.me.event.result;

import com.imc.me.domain.Trade;
import java.util.List;

public record Accepted(long orderId, List<Trade> fills) implements SubmitResult, AmendResult {}
