package io.github.giovanicaprison.matching.api;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The only thing in this module with behaviour. The interfaces have nothing to test until something
 * implements them.
 */
class InstrumentTest {

  @Test
  @DisplayName("a nonsensical instrument fails at construction rather than later")
  void rejects_configuration_that_cannot_be_matched_against() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new Instrument(1, 0, 1, 1, 100, 4))
        .withMessageContaining("tickSize");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new Instrument(1, 1, 0, 1, 100, 4))
        .withMessageContaining("lotSize");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new Instrument(1, 1, 1, 0, 100, 4))
        .withMessageContaining("minPrice");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new Instrument(1, 1, 1, 100, 99, 4))
        .withMessageContaining("below minPrice");
  }

  @Test
  @DisplayName("a band of one price is allowed")
  void a_single_price_band_is_legal() {
    // Degenerate but meaningful: an instrument halted at one price. Rejecting it here would be this
    // module deciding a policy that belongs to whatever configures the venue.
    assertThatCode(() -> new Instrument(1, 1, 1, 100, 100, 4)).doesNotThrowAnyException();
  }
}
