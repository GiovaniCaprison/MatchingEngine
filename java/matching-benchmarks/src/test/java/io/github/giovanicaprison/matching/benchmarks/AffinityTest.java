package io.github.giovanicaprison.matching.benchmarks;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Only Linux can place a thread, so what is asserted here is the contract rather than the
 * placement: either the thread went where it was asked and the kernel confirms it, or the platform
 * cannot say and the run is told. On this machine it is the second, and on the measurement machine
 * the first.
 */
class AffinityTest {

  @Test
  @DisplayName("a pin either happened or is reported as unavailable, and never throws")
  void a_pin_is_recorded_either_way() {
    final Setting setting = Affinity.pin("engine", 0);

    assertThat(setting.name()).isEqualTo("engine core");
    assertThat(setting.expected()).isEqualTo("0");
    if (Affinity.available()) {
      assertThat(setting.status()).isEqualTo(Setting.Status.OK);
      assertThat(setting.actual()).isEqualTo("0");
    } else {
      assertThat(setting.status()).isEqualTo(Setting.Status.UNAVAILABLE);
      assertThat(setting.satisfied()).isFalse();
    }
  }

  @Test
  @DisplayName("a core that cannot exist is refused rather than silently ignored")
  void an_impossible_core_is_not_a_success() {
    final Setting setting = Affinity.pin("engine", 4_000);

    assertThat(setting.satisfied()).isFalse();
  }
}
