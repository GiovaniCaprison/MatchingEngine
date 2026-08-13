package com.imc.me.util;

import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * A genuinely immutable indexed sequence, for the outbound edge DTOs.
 *
 * <p><b>Why this exists rather than {@code List.copyOf}.</b> API-11.1 forbids any public method
 * returning {@code List}, and it catches record <i>accessors</i> too — {@code Accepted.fills()},
 * {@code Depth.levels()}. So a defensive copy can never satisfy it: {@code List.copyOf} buys
 * immutability by allocating a <i>second</i> time, and still leaves {@code List} in the signature.
 * The rule is not "copy defensively", it is "{@code List} does not appear at the boundary" (OOD-9).
 *
 * <p>The stronger reason is honesty. {@code List.copyOf} returns an unmodifiable <i>view type</i>
 * whose interface still advertises {@code add}/{@code remove}, so callers discover immutability by
 * catching {@code UnsupportedOperationException} at runtime. {@code Seq} has no mutator to call —
 * the guarantee is in the type, which is the whole point of FR-5.5.
 *
 * <p>Backed by a private array with no accessor that leaks it, so there is exactly one copy: the one
 * the builder transfers on {@link Builder#build()}.
 *
 * <p><b>This is an edge type.</b> Core code does not build one of these; it emits primitives into a
 * sink and lets the edge decide whether to materialise anything (OOD-3, OOD-9). Constructing a
 * {@code Seq} on the matching hot path would defeat its purpose.
 *
 * <p>{@code equals}/{@code hashCode} are element-wise, which matters because these sit inside
 * records: a golden test comparing two {@code Accepted} values compares their fills through here.
 */
public final class Seq<T> implements Iterable<T> {

  private static final Seq<Object> EMPTY = new Seq<>(new Object[0]);

  private final Object[] items;

  private Seq(final Object[] items) {
    this.items = items;
  }

  @SuppressWarnings("unchecked")
  public static <T> Seq<T> empty() {
    return (Seq<T>) EMPTY;
  }

  @SafeVarargs
  public static <T> Seq<T> of(final T... items) {
    return items.length == 0 ? empty() : new Seq<>(Arrays.copyOf(items, items.length, Object[].class));
  }

  public static <T> Seq<T> copyOf(final Collection<? extends T> source) {
    return source.isEmpty() ? empty() : new Seq<>(source.toArray());
  }

  public int size() {
    return items.length;
  }

  public boolean isEmpty() {
    return items.length == 0;
  }

  @SuppressWarnings("unchecked")
  public T get(final int index) {
    return (T) items[index];
  }

  @Override
  public Iterator<T> iterator() {
    return new Iterator<>() {
      private int cursor;

      @Override
      public boolean hasNext() {
        return cursor < items.length;
      }

      @Override
      @SuppressWarnings("unchecked")
      public T next() {
        if (cursor >= items.length) throw new NoSuchElementException();
        return (T) items[cursor++];
      }
    };
  }

  @Override
  public boolean equals(final Object other) {
    if (this == other) return true;
    return other instanceof Seq<?> that && Arrays.equals(items, that.items);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(items);
  }

  @Override
  public String toString() {
    return Arrays.toString(items);
  }

  public static <T> Builder<T> builder() {
    return new Builder<>(8);
  }

  public static <T> Builder<T> builder(final int expectedSize) {
    return new Builder<>(Math.max(expectedSize, 1));
  }

  /**
   * Accumulates elements, then hands its array to the finished {@link Seq} without copying.
   *
   * <p>Single-use: {@link #build()} transfers ownership of the array, so the builder is spent
   * afterwards. That is what keeps the total cost at one array per sequence rather than the two that
   * {@code stream().toList()} followed by {@code List.copyOf} pays.
   */
  public static final class Builder<T> {
    private Object[] items;
    private int size;

    private Builder(final int capacity) {
      this.items = new Object[capacity];
    }

    public Builder<T> add(final T item) {
      if (size == items.length) items = Arrays.copyOf(items, size * 2);
      items[size++] = item;
      return this;
    }

    public int size() {
      return size;
    }

    public Seq<T> build() {
      if (size == 0) return empty();
      final Object[] exact = (size == items.length) ? items : Arrays.copyOf(items, size);
      items = null; // spent: any later add() fails loudly rather than mutating a published Seq
      return new Seq<>(exact);
    }
  }
}
