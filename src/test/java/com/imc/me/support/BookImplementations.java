package com.imc.me.support;

import com.imc.me.book.OrderBook;
import com.imc.me.book.TreeMapOrderBook;
import com.imc.me.matching.PriceTimeMatcher;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.params.provider.Arguments;

/**
 * Every book implementation, so that one suite can hold all of them to the same behaviour.
 *
 * <p>Implementations are meant to be compared, and a comparison is only worth having once each one
 * has been shown to do the same thing. Adding a rung to the ladder means adding a line here, after
 * which the whole corpus and the whole correctness suite run against it.
 */
public final class BookImplementations {

  private BookImplementations() {}

  /** A book implementation and the name it is reported under. */
  public record Named(String name, Supplier<OrderBook> book) {
    @Override
    public String toString() {
      return name;
    }
  }

  public static List<Named> list() {
    return List.of(new Named("TreeMap", () -> new TreeMapOrderBook(new PriceTimeMatcher())));
  }

  /** The canonical implementation, used when a fixture is regenerated. */
  public static Named reference() {
    return list().get(0);
  }

  /** For {@code @MethodSource}, via {@link AcrossBooks}. */
  public static Stream<Arguments> all() {
    return list().stream().map(Arguments::of);
  }
}
