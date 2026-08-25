package io.github.giovanicaprison.matching.benchmarks;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Only Linux has {@code perf_event_open}, so what is asserted here is the contract on both
 * platforms: a counter set opens and counts, or it does not open and nothing claims otherwise. On
 * this machine it is the second.
 */
class CountersTest {

  @Test
  @DisplayName("a counter set either opens and counts, or does not open")
  void counters_open_or_say_they_cannot() {
    final Set<Counter> wanted = Counter.few();

    try (Counters counters = Counters.open(wanted).orElse(null)) {
      if (counters == null) {
        assertThat(Counters.available())
            .as("a platform that cannot open these should not claim it can")
            .isFalse();
        return;
      }
      final Counters.Reading before = counters.read();
      long sum = 0;
      for (int spin = 0; spin < 1_000_000; spin++) {
        sum += spin;
      }
      final Counters.Reading after = counters.read();

      assertThat(sum).isPositive();
      assertThat(after.since(before))
          .containsOnlyKeys(wanted.toArray(Counter[]::new))
          .allSatisfy((counter, count) -> assertThat(count).isNotNegative());
      assertThat(after.since(before).get(Counter.INSTRUCTIONS))
          .as("a million additions retire more than a million instructions")
          .isGreaterThan(1_000_000L);
    }
  }

  @Test
  @DisplayName("availability is learned from the kernel rather than from the platform's name")
  void availability_is_probed_rather_than_assumed() {
    // Every system has syscall, read and close, and the number is Linux's, so a non-Linux box is
    // unavailable outright. A Linux box is the harder case: the first shared runner this ran on
    // had the syscall and refused it, so availability has to mean the kernel said yes.
    if (!"Linux".equals(System.getProperty("os.name"))) {
      assertThat(Counters.available()).isFalse();
      return;
    }
    final Optional<Counters> opened = Counters.open(Counter.few());
    opened.ifPresent(Counters::close);
    assertThat(Counters.available())
        .as("what available() claims has to be what open() delivers")
        .isEqualTo(opened.isPresent());
  }

  @Test
  @DisplayName("asking for nothing opens nothing")
  void an_empty_set_opens_nothing() {
    assertThat(Counters.open(EnumSet.noneOf(Counter.class))).isEmpty();
  }

  @Test
  @DisplayName("the small set is small enough for any processor's slots")
  void the_default_set_fits() {
    assertThat(Counter.few()).hasSize(4).contains(Counter.INSTRUCTIONS, Counter.CYCLES);
  }
}
