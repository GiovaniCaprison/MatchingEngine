package com.imc.me.event.result;

import com.imc.me.domain.Trade;
import java.util.List;

public record Cancelled(long orderId, List<Trade> fills) implements CancelResult {}
