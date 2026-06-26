package com.imc.me.explicit;

import com.imc.me.support.Requirement;
import com.imc.me.support.TestTags;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Output is a single typed result owned entirely by the edge. */
@Tag(TestTags.FAST)
@DisplayName("Explicit | Boundary validation")
class ValidationTest {

  @Nested
  @DisplayName("VR-1: Quantity validity")
  class Quantity {
    @ParameterizedTest(name = "qty={0} is rejected")
    @ValueSource(longs = {0, -1, -100})
    @Requirement("VR-1.1")
    @DisplayName("VR-1.1: zero or negative quantity is rejected")
    void non_positive_qty_rejected(long qty) {
      // TODO: submit with qty -> Rejected(NON_POSITIVE_QTY)
    }
  }

  @Nested
  @DisplayName("VR-2: Price validity")
  class Price {
    @ParameterizedTest(name = "price={0} is rejected")
    @ValueSource(longs = {0, -1})
    @Requirement("VR-2.1")
    @DisplayName("VR-2.1: a non-positive limit price is rejected")
    void non_positive_price_rejected(long price) {
      // TODO: submit limit with price -> Rejected(NON_POSITIVE_PRICE)
    }

    @Test
    @Requirement("VR-2.2")
    @DisplayName("VR-2.2: a price off the instrument tick / precision is rejected")
    void off_tick_price_rejected() {
      // TODO: submit a price that is not a multiple of instrument.tickSize()
      //       -> Rejected(TICK_VIOLATION). Needs the Instrument record (Step 1).
    }
  }

  @Nested
  @DisplayName("VR-3: Empty book")
  class EmptyBook {
    @Test
    @Requirement("VR-3.1")
    @DisplayName("VR-3.1: a market order against an empty book is handled cleanly")
    void market_against_empty_book() {
      // TODO: market into empty book -> Accepted with no fills, nothing rests
    }

    @Test
    @Requirement("VR-3.2")
    @DisplayName("VR-3.2: a marketable limit with no liquidity rests")
    void marketable_limit_no_liquidity_rests() {
      // TODO: aggressive limit into empty book -> rests at its price
    }
  }

  @Nested
  @DisplayName("API-8: Boundary guarantees (behavioural form)")
  class Boundary {
    @Test
    @Requirement("API-8.1")
    @DisplayName("API-8.1: inputs are validated at the API boundary")
    void validated_at_boundary() {
      // TODO: invalid submit -> Rejected (validation happened before matching)
    }

    @Test
    @Requirement("API-8.2")
    @DisplayName("API-8.2: invalid input never mutates engine state")
    void invalid_input_no_state_change() {
      // TODO: snapshot book, submit invalid, assert book identical + no event
    }
  }
}
