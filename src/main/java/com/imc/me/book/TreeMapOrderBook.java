package com.imc.me.book;

import com.imc.me.domain.Order;
import com.imc.me.domain.OrderSide;
import com.imc.me.domain.Trade;
import com.imc.me.event.dto.Depth;
import com.imc.me.event.dto.OrderStatus;
import com.imc.me.event.dto.TopOfBook;
import com.imc.me.event.result.Accepted;
import com.imc.me.event.result.AmendResult;
import com.imc.me.event.result.CancelResult;
import com.imc.me.event.result.Cancelled;
import com.imc.me.event.result.NotFound;
import com.imc.me.event.result.SubmitResult;
import com.imc.me.matching.Matcher;
import java.util.List;

/**
 * The single writer. Owns both sides (each with its own id index) and holds the Matcher as a
 * strategy it drives over the opposing side. The MatchingEngine validates/sequences commands and
 * calls in here; it never touches matching mechanics keeping the Matcher inside this boundary,
 * rather than having the engine coordinate the book and the matcher, is the whole point.
 */
public final class TreeMapOrderBook implements OrderBook {
  private final BookSide bids = new TreeMapBookSide(OrderSide.BUY);
  private final BookSide asks = new TreeMapBookSide(OrderSide.SELL);
  private final Matcher matcher;

  public TreeMapOrderBook(Matcher matcher) {
    this.matcher = matcher;
  }

  public SubmitResult submit(Order order) {
    List<Trade> fills = matcher.match(order, opposingSide(order.side()));

    // TODO(Step 4): per-type remainder policy belongs here; LIMIT rests, MARKET/IOC cancel,
    // FOK is all-or-nothing, POST rejects if it would cross. Only LIMIT is in scope for now.
    if (order.getRemainingQty() > 0) {
      sideFor(order.side()).addOrder(order);
    }
    return new Accepted(order.orderId(), fills);
  }

  public AmendResult amend(long orderId) {
    // TODO(FR-4.3/4.4/4.5): qty-decrease reduces in place and keeps priority; increase/reprice
    // unlinks and re-appends (loses priority).
    throw new UnsupportedOperationException("amend not implemented yet");
  }

  public CancelResult cancel(long orderId) {
    Order order = bids.get(orderId);
    BookSide side = bids;
    if (order == null) {
      order = asks.get(orderId);
      side = asks;
    }
    if (order == null) return new NotFound(orderId);

    side.remove(order);
    // TODO: per-order fill history isn't tracked yet, so fills-before-cancellation is empty.
    return new Cancelled(orderId, List.of());
  }

  public TopOfBook topOfBook(OrderSide side) {
    BookSide book = sideFor(side);
    if (book.isEmpty()) return TopOfBook.empty(side);
    PriceLevel best = book.bestLevel();
    return TopOfBook.of(side, best.price(), best.totalQty());
  }

  public Depth depth(OrderSide side) {
    return new Depth(side, sideFor(side).depth());
  }

  public OrderStatus orderStatus(long orderId) {
    // TODO(FR-5.4): filled/cancelled orders leave the resting set, so status isn't answerable
    // from bids/asks alone — this needs an order registry that outlives the book.
    throw new UnsupportedOperationException("orderStatus not implemented yet");
  }

  private BookSide sideFor(OrderSide side) {
    return (side == OrderSide.BUY) ? bids : asks;
  }

  private BookSide opposingSide(OrderSide side) {
    return (side == OrderSide.BUY) ? asks : bids;
  }
}
