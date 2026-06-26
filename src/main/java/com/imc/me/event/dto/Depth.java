package com.imc.me.event.dto;

import com.imc.me.domain.OrderSide;
import java.util.List;

public record Depth(OrderSide side, List<Long> depth) {}
