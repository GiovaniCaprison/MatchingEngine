package com.imc.me.book;

import com.imc.me.domain.OrderSide;
import com.imc.me.event.dto.Depth;
import com.imc.me.event.dto.TopOfBook;

/**
 * The read-only half of a book: queries only, no mutation.
 *
 * <p>This split exists for capability narrowing, not for symmetry (OOD-17). A market-data publisher
 * or a status endpoint is handed one of these and is <i>unable</i> to mutate the book — enforced by
 * the type, so it needs no review discipline and no defensive copying.
 */
public interface OrderBookReader {
  TopOfBook topOfBook(final OrderSide side);

  /**
   * A depth snapshot for one side, best price first, capped at {@code maxLevels} (OOD-10).
   *
   * <p>The cap is a required argument rather than a defaulted one so that a caller cannot ask for
   * "everything" by accident. See {@link BookSide#depth} for why unbounded is a denial of service.
   */
  Depth depth(final OrderSide side, final int maxLevels);

}
