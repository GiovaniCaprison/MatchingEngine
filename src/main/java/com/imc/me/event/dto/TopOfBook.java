package com.imc.me.event.dto;

import com.imc.me.book.PriceLevel;
import com.imc.me.domain.OrderSide;

public record TopOfBook(OrderSide side, PriceLevel topOfBook) {}
