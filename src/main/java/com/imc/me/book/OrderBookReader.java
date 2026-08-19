package com.imc.me.book;

import com.imc.me.domain.OrderSide;
import com.imc.me.event.dto.Depth;
import com.imc.me.event.dto.TopOfBook;

/**
 * The read-only half of a book. Handing one of these to a market data publisher or a status
 * endpoint makes it unable to mutate the book, which needs no review discipline and no defensive
 * copying (OOD-17).
 */
public interface OrderBookReader {
  TopOfBook topOfBook(final OrderSide side);

  /**
   * A depth snapshot for one side, best price first, capped at {@code maxLevels} (OOD-10). The cap
   * is a required argument so a caller cannot ask for everything by accident. See {@link
   * BookSide#depth} for why unbounded is a denial of service.
   */
  Depth depth(final OrderSide side, final int maxLevels);
}
