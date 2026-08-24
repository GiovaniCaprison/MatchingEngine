package io.github.giovanicaprison.matching.benchmarks;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The kernel writes these lists; reading them wrong would make the isolation check say nothing. */
class CpuListTest {

  @Test
  @DisplayName("a range covers its ends and nothing outside them")
  void a_range_is_inclusive() {
    final CpuList cores = CpuList.parse("2-15");

    assertThat(cores.contains(2)).isTrue();
    assertThat(cores.contains(15)).isTrue();
    assertThat(cores.contains(1)).isFalse();
    assertThat(cores.contains(16)).isFalse();
  }

  @Test
  @DisplayName("ranges and single cores mix, as the kernel writes them")
  void a_list_can_be_both() {
    final CpuList cores = CpuList.parse("0,2-4,20");

    assertThat(cores.contains(0)).isTrue();
    assertThat(cores.contains(3)).isTrue();
    assertThat(cores.contains(20)).isTrue();
    assertThat(cores.contains(1)).isFalse();
    assertThat(cores.contains(19)).isFalse();
  }

  @Test
  @DisplayName("nothing to read is an empty list rather than a failure")
  void an_absent_list_is_empty() {
    assertThat(CpuList.parse(null).isEmpty()).isTrue();
    assertThat(CpuList.parse("  ").isEmpty()).isTrue();
    assertThat(CpuList.parse("2-15").isEmpty()).isFalse();
  }

  @Test
  @DisplayName("a list this cannot read keeps whatever of it made sense")
  void a_malformed_list_is_partial_rather_than_fatal() {
    final CpuList cores = CpuList.parse("2,nonsense,7-8");

    assertThat(cores.contains(2)).isTrue();
    assertThat(cores.contains(8)).isTrue();
  }
}
