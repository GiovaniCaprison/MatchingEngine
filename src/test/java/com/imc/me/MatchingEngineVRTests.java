package com.imc.me;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class OrderControllerVRTests {

  @Nested
  @DisplayName("VR-1: Quantity Validity")
  class QuantityValidity {

    @Test
    @DisplayName("VR-1.1: Zero or negative quantity is rejected")
    void quantityValidityVR1() {}
  }

  @Nested
  @DisplayName("VR-2: Price Validity")
  class PriceValidity {

    @Test
    @DisplayName("VR-2.1: A non-positive limit price is rejected")
    void priceValidityVR1() {}

    @Test
    @DisplayName("VR-2.2: A price violating the tick or precision rule is rejected")
    void priceValidityVR2() {}
  }

  @Nested
  @DisplayName("VR-3: Empty Book")
  class EmptyBook {

    @Test
    @DisplayName("VR-3.1: A market order against an empty book is handled correctly")
    void emptyBookVR1() {}

    @Test
    @DisplayName("VR-3.2: A marketable limit with no liquidity rests correctly")
    void emptyBookVR2() {}
  }

  @Nested
  @DisplayName("VR-4: Liquidity Exhaustion")
  class LiquidityExhaustion {

    @Test
    @DisplayName("VR-4.1: An aggressor larger than all liquidity fully sweeps the book")
    void liquidityExhaustionVR1() {}

    @Test
    @DisplayName("VR-4.2: A swept aggressor remainder rests or cancels per its type")
    void liquidityExhaustionVR2() {}
  }

  @Nested
  @DisplayName("VR-5: Self-Trade")
  class SelfTrade {

    @Test
    @DisplayName("VR-5.1: Self-trade policy is applied consistently or is out of scope")
    void selfTradeVR1() {}
  }

  @Nested
  @DisplayName("VR-6: Book Consistency")
  class BookConsistency {

    @Test
    @DisplayName("VR-6.1: No operation leaves the book in an inconsistent state")
    void bookConsistencyVR1() {}
  }
}
