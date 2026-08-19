package com.imc.me.util;

import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * A genuinely immutable indexed sequence, for the outbound edge DTOs.
 *
 * <p>API-11.1 forbids any public method returning {@code List}, and it catches record accessors
 * too, so a defensive copy can never satisfy it: {@code List.copyOf} allocates a second time and
 * still leaves {@code List} in the signature (OOD-9).
 *
 * <p>The stronger reason is honesty. {@code List.copyOf} returns a view whose interface still
 * advertises {@code add} and {@code remove}, so a caller discovers immutability by catching {@code
 * UnsupportedOperationException} at runtime. {@code Seq} has no mutator to call, so the guarantee
 * is in the type, which is what FR-5.5 is asking for.
 *
 * <p>Backed by a private array that nothing leaks, so there is one copy: the one the builder
 * transfers on {@link Builder#build()}.
 *
 * <p>An edge type. Core code emits primitives into a sink and lets the edge decide whether to
 * materialise anything (OOD-3), so building one of these on the hot path would defeat the purpose.
 *
 * <p>{@code equals} and {@code hashCode} are element-wise, which matters because these sit inside
 * records: comparing two {@code Accepted} values compares their fills through here.
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
    return items.length == 0
        ? empty()
        : new Seq<>(Arrays.copyOf(items, items.length, Object[].class));
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
    return new Builder<>(expectedSize > 1 ? expectedSize : 1);
  }

  /**
   * Accumulates elements, then hands its array to the finished {@link Seq} without copying.
   *
   * <p>Single-use: {@link #build()} transfers ownership of the array, so the builder is spent
   * afterwards. That is what keeps the total cost at one array per sequence rather than the two
   * that {@code stream().toList()} followed by {@code List.copyOf} pays.
   */
  public static final class Builder<T> {
    private Object[] items;
    private int size;

    private Builder(final int capacity) {
      this.items = new Object[capacity];
    }

    public Builder<T> add(final T item) {
      if (items == null) throw new IllegalStateException("builder already spent by build()");
      if (size == items.length) items = Arrays.copyOf(items, size * 2);
      items[size++] = item;
      return this;
    }

    public int size() {
      return size;
    }

    public Seq<T> build() {
      if (items == null) throw new IllegalStateException("builder already spent by build()");
      if (size == 0) return empty();
      final Object[] exact = (size == items.length) ? items : Arrays.copyOf(items, size);
      items = null; // spent: any later add() fails loudly rather than mutating a published Seq
      return new Seq<>(exact);
    }
  }
}
