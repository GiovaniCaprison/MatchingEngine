package com.imc.me.event.result;

import com.imc.me.domain.Trade;
import java.util.List;

public record Matched(List<Trade> fills) implements MatchResult {}
