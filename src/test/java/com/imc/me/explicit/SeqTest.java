package com.imc.me.explicit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imc.me.domain.Trade;
import com.imc.me.support.Requirement;
import com.imc.me.support.TestTags;
import com.imc.me.util.Seq;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The immutable outbound sequence that replaces {@code List} at the boundary (API-11.1, OOD-9). */
@Tag(TestTags.FAST)
@DisplayName("Explicit | Seq")
class SeqTest {

  @Test
  @Requirement("API-11.1")
  @DisplayName("API-11.1: a Seq cannot be changed through the collection it was built from")
  void source_mutation_does_not_leak_in() {
    final List<Trade> source = new ArrayList<>();
    source.add(new Trade(1L, 2L, 100L, 5L));
    final Seq<Trade> seq = Seq.copyOf(source);

    source.add(new Trade(3L, 4L, 101L, 7L));
    source.clear();

    assertThat(seq.size()).isEqualTo(1);
    assertThat(seq.get(0)).isEqualTo(new Trade(1L, 2L, 100L, 5L));
  }

  @Test
  @Requirement("API-11.1")
  @DisplayName("API-11.1: a varargs Seq does not alias the caller's array")
  void varargs_array_is_not_aliased() {
    final Trade[] source = {new Trade(1L, 2L, 100L, 5L)};
    final Seq<Trade> seq = Seq.of(source);

    source[0] = new Trade(9L, 9L, 999L, 9L);

    assertThat(seq.get(0)).isEqualTo(new Trade(1L, 2L, 100L, 5L));
  }

  @Test
  @Requirement("FR-5.5")
  @DisplayName("FR-5.5: equality is element-wise so results compare as values")
  void equality_is_element_wise() {
    final Seq<String> left = Seq.of("a", "b");
    final Seq<String> right = Seq.<String>builder().add("a").add("b").build();

    // This is what makes a golden test comparing two Accepted records work: record equality
    // delegates straight through to here.
    assertThat(left).isEqualTo(right);
    assertThat(left.hashCode()).isEqualTo(right.hashCode());
    assertThat(left).isNotEqualTo(Seq.of("a"));
    assertThat(left.toString()).isEqualTo("[a, b]");
  }

  @Test
  @Requirement("FR-5.5")
  @DisplayName("FR-5.5: empty sequences are a shared instance and read as empty")
  void empty_is_canonical() {
    assertThat(Seq.empty()).isSameAs(Seq.of());
    assertThat(Seq.copyOf(List.of())).isSameAs(Seq.empty());
    assertThat(Seq.<String>builder().build()).isSameAs(Seq.empty());
    assertThat(Seq.empty().isEmpty()).isTrue();
    assertThat(Seq.empty().size()).isZero();
    assertThat(Seq.empty()).isEqualTo(Seq.empty());
  }

  @Test
  @Requirement("FR-5.5")
  @DisplayName("FR-5.5: iteration yields every element in order then stops")
  void iterates_in_order() {
    final Seq<String> seq = Seq.of("a", "b", "c");

    final List<String> seen = new ArrayList<>();
    for (final String s : seq) seen.add(s);
    assertThat(seen).containsExactly("a", "b", "c");

    final var it = seq.iterator();
    it.next();
    it.next();
    it.next();
    assertThat(it.hasNext()).isFalse();
    assertThatThrownBy(it::next).isInstanceOf(NoSuchElementException.class);
  }

  @Test
  @Requirement("API-11.1")
  @DisplayName("API-11.1: a builder grows past its hint and is spent once built")
  void builder_grows_and_is_single_use() {
    final Seq.Builder<Integer> builder = Seq.builder(1);
    for (int i = 0; i < 9; i++) builder.add(i);
    assertThat(builder.size()).isEqualTo(9);

    final Seq<Integer> built = builder.build();
    assertThat(built.size()).isEqualTo(9);
    assertThat(built.get(8)).isEqualTo(8);

    // The builder hands its array to the Seq without copying, so it must be spent afterwards:
    // a later add() has to fail loudly rather than mutate an already-published Seq.
    assertThatThrownBy(() -> builder.add(99)).isInstanceOf(NullPointerException.class);
    assertThat(built.size()).isEqualTo(9);
  }

  @Test
  @Requirement("API-11.1")
  @DisplayName("API-11.1: out-of-range access fails rather than returning null")
  void out_of_range_access_fails() {
    final Seq<String> seq = Seq.of("a");
    assertThatThrownBy(() -> seq.get(1)).isInstanceOf(ArrayIndexOutOfBoundsException.class);
    assertThatThrownBy(() -> seq.get(-1)).isInstanceOf(ArrayIndexOutOfBoundsException.class);
  }
}
