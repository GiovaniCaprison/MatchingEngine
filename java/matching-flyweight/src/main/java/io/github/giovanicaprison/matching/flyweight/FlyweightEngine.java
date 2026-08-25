package io.github.giovanicaprison.matching.flyweight;

import io.github.giovanicaprison.matching.api.EventPublisher;
import io.github.giovanicaprison.matching.api.MatchingEngine;
import io.github.giovanicaprison.matching.protocol.AllocationAlgorithm;
import io.github.giovanicaprison.matching.protocol.CancelOrderDecoder;
import io.github.giovanicaprison.matching.protocol.InstrumentDefinitionDecoder;
import io.github.giovanicaprison.matching.protocol.MassCancelDecoder;
import io.github.giovanicaprison.matching.protocol.MessageHeaderDecoder;
import io.github.giovanicaprison.matching.protocol.NewOrderDecoder;
import io.github.giovanicaprison.matching.protocol.PricingInstruction;
import io.github.giovanicaprison.matching.protocol.RejectReason;
import io.github.giovanicaprison.matching.protocol.RemoveReason;
import io.github.giovanicaprison.matching.protocol.ReplaceOrderDecoder;
import io.github.giovanicaprison.matching.protocol.SessionState;
import io.github.giovanicaprison.matching.protocol.SessionStateChangeDecoder;
import io.github.giovanicaprison.matching.protocol.Side;
import io.github.giovanicaprison.matching.protocol.TimeInForce;
import java.nio.ByteOrder;
import java.util.List;
import org.agrona.DirectBuffer;

/**
 * Rung three: the same venue as the naive engine, expressed as index arithmetic over a handful of
 * primitive arrays.
 *
 * <p>The step from the rung below isolates indirection. The pooled engine already allocated nothing
 * in steady state; what it still paid was pointers. An order was an object somewhere on the heap, a
 * price level was a tree node found by descending other tree nodes, and a command was read through
 * a decoder holding a buffer it re-fetched per field. Every one of those is a load whose address
 * depends on the previous load, which is the one latency a modern core cannot hide. This rung
 * replaces each of them with arithmetic it can: an order is an int slot in one slab of longs
 * ({@link Slab}), a price level is an index into a flat ladder ({@link Ladder}), the best price is
 * a bit in a summary word, and a command's fields are read in place from the buffer at the schema's
 * fixed offsets, each offset taken from the generated decoder so the schema stays the source of
 * truth.
 *
 * <p>Where the remaining nanoseconds go, on the continuous price-time path a venue actually runs:
 * one read of the header's template id and a jump; the command's fields into locals, one bounded
 * load each; validation as a run of compares against fields already in registers, with the tick
 * division fused into the tick check so the price pays for one divide (VR-2.2); then per fill, one
 * cache line of the resting order's slot, one line of its level's two words, and the event's puts
 * into the claimed space. The take loop carries no allocation, no virtual call, no side branch,
 * since the bid ladder ranks its ticks reversed and both sides cross by the same comparison, and no
 * search, since the best rank is cached and its successor is three trailing-zero counts away when a
 * level empties. The auction and pro-rata paths exist behind one predictable branch each, hoisted
 * off the continuous path (P-7, P-16).
 *
 * <p>The commands flow through the same decisions in the same order as every rung below, and the
 * differential holds the output byte identical to the arbiter's (NFR-5.1). The Epsilon proof
 * carries over from the pooled rung: the steady state allocates nothing, shown by a collector that
 * never collects (NFR-4.3).
 */
public final class FlyweightEngine implements MatchingEngine {

  private static final ByteOrder WIRE = ByteOrder.LITTLE_ENDIAN;

  // The schema's numbers, pinned once from the generated codecs so nothing here is hand-copied.
  private static final int HEADER_TEMPLATE = MessageHeaderDecoder.templateIdEncodingOffset();
  private static final int BODY = MessageHeaderDecoder.ENCODED_LENGTH;

  /** BUY encodes to zero, so {@code side ^ 1} is always the opposite side. */
  private static final int BUY = Side.BUY.value();

  private static final int SELL = Side.SELL.value();
  private static final int LIMIT = PricingInstruction.LIMIT.value();
  private static final int MARKET = PricingInstruction.MARKET.value();
  private static final int DAY = TimeInForce.DAY.value();
  private static final int IOC = TimeInForce.IMMEDIATE_OR_CANCEL.value();
  private static final int FOK = TimeInForce.FILL_OR_KILL.value();

  private static final int PRE_OPEN = SessionState.PRE_OPEN.value();
  private static final int OPENING_AUCTION = SessionState.OPENING_AUCTION.value();
  private static final int CONTINUOUS = SessionState.CONTINUOUS.value();
  private static final int CLOSING_AUCTION = SessionState.CLOSING_AUCTION.value();
  private static final int CLOSED = SessionState.CLOSED.value();

  private static final int NON_POSITIVE_QUANTITY = RejectReason.NON_POSITIVE_QUANTITY.value();
  private static final int LOT_VIOLATION = RejectReason.LOT_VIOLATION.value();
  private static final int NON_POSITIVE_PRICE = RejectReason.NON_POSITIVE_PRICE.value();
  private static final int TICK_VIOLATION = RejectReason.TICK_VIOLATION.value();
  private static final int STATIC_BAND_VIOLATION = RejectReason.STATIC_BAND_VIOLATION.value();
  private static final int DYNAMIC_BAND_VIOLATION = RejectReason.DYNAMIC_BAND_VIOLATION.value();
  private static final int INVALID_FIELDS = RejectReason.INVALID_FIELDS.value();
  private static final int MINIMUM_QUANTITY_ABOVE_ORDER =
      RejectReason.MINIMUM_QUANTITY_ABOVE_ORDER.value();
  private static final int DISPLAY_QUANTITY_ABOVE_ORDER =
      RejectReason.DISPLAY_QUANTITY_ABOVE_ORDER.value();
  private static final int MINIMUM_QUANTITY_NOT_MET = RejectReason.MINIMUM_QUANTITY_NOT_MET.value();
  private static final int WOULD_CROSS = RejectReason.WOULD_CROSS.value();
  private static final int FILL_OR_KILL_UNFILLABLE = RejectReason.FILL_OR_KILL_UNFILLABLE.value();
  private static final int STATE_NOT_PERMITTED = RejectReason.STATE_NOT_PERMITTED.value();
  private static final int UNKNOWN_ORDER = RejectReason.UNKNOWN_ORDER.value();
  private static final int QUANTITY_BELOW_EXECUTED = RejectReason.QUANTITY_BELOW_EXECUTED.value();

  private static final int CANCELLED = RemoveReason.CANCELLED.value();
  private static final int REPLACED = RemoveReason.REPLACED.value();
  private static final int MASS_CANCELLED = RemoveReason.MASS_CANCELLED.value();
  private static final int IOC_REMAINDER = RemoveReason.IMMEDIATE_OR_CANCEL_REMAINDER.value();
  private static final int SELF_MATCH_PREVENTED = RemoveReason.SELF_MATCH_PREVENTED.value();

  private static final int PRO_RATA = AllocationAlgorithm.PRO_RATA.value();

  // Field offsets inside each command's body, each tied to its schema field by the decoder.
  private static final int DEFINITION_INSTRUMENT =
      InstrumentDefinitionDecoder.frameEncodingOffset();
  private static final int DEFINITION_TICK = InstrumentDefinitionDecoder.tickSizeEncodingOffset();
  private static final int DEFINITION_LOT = InstrumentDefinitionDecoder.lotSizeEncodingOffset();
  private static final int DEFINITION_MIN = InstrumentDefinitionDecoder.minPriceEncodingOffset();
  private static final int DEFINITION_MAX = InstrumentDefinitionDecoder.maxPriceEncodingOffset();
  private static final int DEFINITION_BAND = InstrumentDefinitionDecoder.bandWidthEncodingOffset();
  private static final int DEFINITION_OPEN =
      InstrumentDefinitionDecoder.openingReferenceEncodingOffset();
  private static final int DEFINITION_ALLOCATION =
      InstrumentDefinitionDecoder.allocationEncodingOffset();

  private static final int NEW_CLIENT = NewOrderDecoder.clientOrderIdEncodingOffset();
  private static final int NEW_PARTICIPANT = NewOrderDecoder.participantIdEncodingOffset();
  private static final int NEW_SIDE = NewOrderDecoder.sideEncodingOffset();
  private static final int NEW_PRICING = NewOrderDecoder.pricingEncodingOffset();
  private static final int NEW_TIF = NewOrderDecoder.timeInForceEncodingOffset();
  private static final int NEW_FLAGS = NewOrderDecoder.flagsEncodingOffset();
  private static final int NEW_PRICE = NewOrderDecoder.priceEncodingOffset();
  private static final int NEW_QUANTITY = NewOrderDecoder.quantityEncodingOffset();
  private static final int NEW_MIN = NewOrderDecoder.minQuantityEncodingOffset();
  private static final int NEW_DISPLAY = NewOrderDecoder.displayQuantityEncodingOffset();
  private static final int NEW_TRIGGER = NewOrderDecoder.triggerPriceEncodingOffset();
  private static final int NEW_SMP = NewOrderDecoder.smpIdEncodingOffset();

  private static final int CANCEL_CLIENT = CancelOrderDecoder.clientOrderIdEncodingOffset();
  private static final int CANCEL_PARTICIPANT = CancelOrderDecoder.participantIdEncodingOffset();

  private static final int REPLACE_CLIENT = ReplaceOrderDecoder.clientOrderIdEncodingOffset();
  private static final int REPLACE_PARTICIPANT = ReplaceOrderDecoder.participantIdEncodingOffset();
  private static final int REPLACE_QUANTITY = ReplaceOrderDecoder.quantityEncodingOffset();
  private static final int REPLACE_PRICE = ReplaceOrderDecoder.priceEncodingOffset();

  private static final int MASS_CLIENT = MassCancelDecoder.clientOrderIdEncodingOffset();
  private static final int MASS_PARTICIPANT = MassCancelDecoder.participantIdEncodingOffset();

  private static final int SESSION_STATE = SessionStateChangeDecoder.stateEncodingOffset();

  private final Feed feed;
  private final Slab slab = new Slab(1 << 16);
  private final Triggers triggers = new Triggers(slab);
  private final Auction auction = new Auction();

  private final IntScratch pending = new IntScratch();
  private final IntScratch snapshot = new IntScratch();
  private final IntScratch gathered = new IntScratch();
  private final IntScratch buys = new IntScratch();
  private final IntScratch sells = new IntScratch();

  private Book book;

  private long tickSize = 1;
  private long lotSize = 1;
  private long minPrice;
  private long maxPrice;
  private long bandWidth;
  private long baseTick;
  private boolean proRata;
  private int state = PRE_OPEN;
  private long reference;
  private long lastExecuted;
  private long nextOrderId = 1;
  private long nextExecutionId = 1;
  private long arrival;
  private long indicativePrice;
  private long indicativeQuantity;

  FlyweightEngine(final EventPublisher events) {
    this.feed = new Feed(events);
  }

  /** The book itself, for the invariants that hold its bookkeeping to its queues (NFR-3.1). */
  Book book() {
    return book;
  }

  Slab slab() {
    return slab;
  }

  List<Integer> waiting() {
    return triggers.stops();
  }

  @Override
  public void onCommand(final DirectBuffer buffer, final int offset, final int length) {
    final int body = offset + BODY;
    switch (buffer.getShort(offset + HEADER_TEMPLATE, WIRE) & 0xFFFF) {
      case NewOrderDecoder.TEMPLATE_ID -> enter(buffer, body);
      case CancelOrderDecoder.TEMPLATE_ID -> cancel(buffer, body);
      case ReplaceOrderDecoder.TEMPLATE_ID -> replace(buffer, body);
      case MassCancelDecoder.TEMPLATE_ID -> massCancel(buffer, body);
      case SessionStateChangeDecoder.TEMPLATE_ID -> changeState(buffer, body);
      case InstrumentDefinitionDecoder.TEMPLATE_ID -> define(buffer, body);
      default ->
          throw new IllegalArgumentException(
              "template "
                  + (buffer.getShort(offset + HEADER_TEMPLATE, WIRE) & 0xFFFF)
                  + " is not a command (P-14)");
    }
  }

  /**
   * The definition arrives once, before every other command (FR-1.1), which is what licenses the
   * ladder: the tick range is known, so the levels can be an array and this is the one allocation
   * the engine's life holds after construction.
   */
  private void define(final DirectBuffer buffer, final int body) {
    tickSize = buffer.getLong(body + DEFINITION_TICK, WIRE);
    lotSize = buffer.getLong(body + DEFINITION_LOT, WIRE);
    minPrice = buffer.getLong(body + DEFINITION_MIN, WIRE);
    maxPrice = buffer.getLong(body + DEFINITION_MAX, WIRE);
    bandWidth = buffer.getLong(body + DEFINITION_BAND, WIRE);
    reference = buffer.getLong(body + DEFINITION_OPEN, WIRE);
    proRata = buffer.getByte(body + DEFINITION_ALLOCATION) == PRO_RATA;
    baseTick = (minPrice + tickSize - 1) / tickSize;
    book = new Book(slab, tickSize, baseTick, (int) (maxPrice / tickSize - baseTick + 1));
    feed.instrument(buffer.getInt(body + DEFINITION_INSTRUMENT, WIRE));
  }

  // Order entry ---------------------------------------------------------------------------------

  private void enter(final DirectBuffer buffer, final int body) {
    final long clientOrderId = buffer.getLong(body + NEW_CLIENT, WIRE);
    final int participantId = buffer.getInt(body + NEW_PARTICIPANT, WIRE);
    final int side = buffer.getByte(body + NEW_SIDE);
    final int pricing = buffer.getByte(body + NEW_PRICING);
    final int timeInForce = buffer.getByte(body + NEW_TIF);
    final boolean postOnly = (buffer.getByte(body + NEW_FLAGS) & 1) != 0;
    final long price = buffer.getLong(body + NEW_PRICE, WIRE);
    final long quantity = buffer.getLong(body + NEW_QUANTITY, WIRE);
    final long minQuantity = buffer.getLong(body + NEW_MIN, WIRE);
    final long displayQuantity = buffer.getLong(body + NEW_DISPLAY, WIRE);
    final long triggerPrice = buffer.getLong(body + NEW_TRIGGER, WIRE);
    final long smpId = buffer.getLong(body + NEW_SMP, WIRE);

    final long verdict =
        refusalOrTick(
            side,
            pricing,
            timeInForce,
            postOnly,
            price,
            quantity,
            minQuantity,
            displayQuantity,
            triggerPrice,
            smpId);
    if (verdict < 0) {
      feed.rejected(clientOrderId, participantId, reasonOf(verdict));
      return;
    }
    final long id = nextOrderId++;
    final int slot = slab.acquire();
    slab.init(
        slot,
        id,
        clientOrderId,
        participantId,
        side,
        pricing,
        timeInForce,
        postOnly,
        (int) verdict,
        quantity,
        minQuantity,
        displayQuantity,
        triggerPrice,
        smpId,
        ++arrival,
        0);
    feed.accepted(id, clientOrderId, participantId);
    admit(slot, side);
  }

  private void admit(final int slot, final int side) {
    if (slab.stop(slot)) {
      triggers.add(slot);
      fireTriggers();
      return;
    }
    if (state == CONTINUOUS) {
      match(slot, side);
      fireTriggers();
    }
    settle(slot, side);
  }

  private void settle(final int slot, final int side) {
    if (slab.remaining(slot) == 0) {
      slab.release(slot);
      return;
    }
    if (slab.pricing(slot) == LIMIT && slab.timeInForce(slot) <= DAY) {
      slab.rest(slot, ++arrival);
      book.add(side, slot);
      feed.rested(slab.id(slot), side, book.priceOfTick(slab.tick(slot)), slab.displayed(slot));
      reportIndicative();
      return;
    }
    feed.removed(slab.id(slot), slab.remaining(slot), IOC_REMAINDER);
    slab.release(slot);
  }

  // Matching ------------------------------------------------------------------------------------

  private void match(final int taker, final int side) {
    final int limitRank = limitRankOf(taker, side);
    final long smpId = slab.smpId(taker);
    final boolean proRata = this.proRata;
    while (slab.remaining(taker) > 0) {
      final int resting = book.nextToTake(side, limitRank);
      if (resting == 0) {
        return;
      }
      if (prevented(smpId, resting, side)) {
        continue;
      }
      if (proRata) {
        proRataTake(taker, side, limitRank, slab.tick(resting));
      } else {
        takeExactly(taker, side, resting, Math.min(slab.remaining(taker), slab.displayed(resting)));
      }
    }
  }

  /** The taker's limit as a rank in the resting side's own space; a market order reaches all. */
  private int limitRankOf(final int taker, final int side) {
    return slab.pricing(taker) == MARKET
        ? book.marketLimit()
        : book.rankOf(side ^ 1, slab.tick(taker));
  }

  private boolean prevented(final long smpId, final int resting, final int takerSide) {
    if (smpId == 0 || smpId != slab.smpId(resting)) {
      return false;
    }
    // (FR-3.7) The resting order is removed and the walk continues.
    book.remove(takerSide ^ 1, resting);
    feed.removed(slab.id(resting), slab.displayed(resting), SELF_MATCH_PREVENTED);
    slab.release(resting);
    return true;
  }

  private void takeExactly(
      final int taker, final int side, final int resting, final long quantity) {
    final long price = book.priceOfTick(slab.tick(resting));
    slab.take(taker, quantity);
    final long shownBefore = slab.displayed(resting);
    final boolean replenishes = slab.take(resting, quantity);
    feed.executed(nextExecutionId++, slab.id(taker), slab.id(resting), price, quantity);
    reference = price;
    lastExecuted = price;
    final int restingSide = side ^ 1;
    if (slab.remaining(resting) == 0) {
      book.quantitiesChanged(restingSide, resting, -shownBefore, -quantity);
      book.remove(restingSide, resting);
      slab.release(resting);
      return;
    }
    if (replenishes) {
      // One delta covers the take and the reveal: the level goes from holding what this order
      // showed before the execution to holding its fresh tranche (FR-5.4).
      slab.rest(resting, ++arrival);
      book.requeued(restingSide, resting, slab.displayed(resting) - shownBefore, -quantity);
      feed.rested(slab.id(resting), restingSide, price, slab.displayed(resting));
      return;
    }
    book.quantitiesChanged(restingSide, resting, slab.displayed(resting) - shownBefore, -quantity);
  }

  private void proRataTake(final int taker, final int side, final int limitRank, final int tick) {
    final int restingSide = side ^ 1;
    final int head = book.headAtRank(restingSide, book.rankOf(restingSide, tick));
    if (head == 0) {
      return;
    }
    // The queue is copied out before anything trades, because a fill unlinks and a replenish
    // re-queues, and the allocation is owed to the queue as it stood (FR-3.4).
    snapshot.clear();
    long available = 0;
    for (int resting = head; resting != 0; resting = slab.next(resting)) {
      snapshot.add(resting);
      available += slab.displayed(resting);
    }
    if (available == 0) {
      return;
    }
    final long wanted = Math.min(slab.remaining(taker), available);
    for (int at = 0; at < snapshot.size(); at++) {
      if (slab.remaining(taker) == 0) {
        break;
      }
      final int resting = snapshot.get(at);
      final long displayed = slab.displayed(resting);
      final long share = wanted * displayed / available / lotSize * lotSize;
      final long quantity = Math.min(Math.min(share, displayed), slab.remaining(taker));
      if (quantity > 0) {
        takeExactly(taker, side, resting, quantity);
      }
    }
    while (slab.remaining(taker) > 0) {
      final int next = book.nextToTake(side, limitRank);
      if (next == 0 || slab.tick(next) != tick) {
        return;
      }
      takeExactly(taker, side, next, Math.min(slab.remaining(taker), slab.displayed(next)));
    }
  }

  // Triggers ------------------------------------------------------------------------------------

  private void fireTriggers() {
    if (lastExecuted == 0) {
      return;
    }
    triggers.fire(lastExecuted, pending);
    int at = 0;
    while (at < pending.size()) {
      final int fired = pending.get(at++);
      feed.triggered(slab.id(fired));
      slab.triggered(fired, ++arrival);
      final int side = slab.side(fired);
      if (state == CONTINUOUS) {
        match(fired, side);
      }
      settle(fired, side);
      triggers.fire(lastExecuted, pending);
    }
    pending.clear();
  }

  // Amend and cancel ----------------------------------------------------------------------------

  private void cancel(final DirectBuffer buffer, final int body) {
    final long clientOrderId = buffer.getLong(body + CANCEL_CLIENT, WIRE);
    final int participantId = buffer.getInt(body + CANCEL_PARTICIPANT, WIRE);
    if (state == CLOSED) {
      feed.rejected(clientOrderId, participantId, STATE_NOT_PERMITTED);
      return;
    }
    final int resting = book.named(participantId, clientOrderId);
    if (resting != 0) {
      book.remove(slab.side(resting), resting);
      feed.removed(slab.id(resting), slab.displayed(resting), CANCELLED);
      slab.release(resting);
      reportIndicative();
      return;
    }
    final int stop = triggers.named(participantId, clientOrderId);
    if (stop != 0) {
      triggers.remove(stop);
      feed.removed(slab.id(stop), slab.remaining(stop), CANCELLED);
      slab.release(stop);
      return;
    }
    feed.rejected(clientOrderId, participantId, UNKNOWN_ORDER);
  }

  private void replace(final DirectBuffer buffer, final int body) {
    final long clientOrderId = buffer.getLong(body + REPLACE_CLIENT, WIRE);
    final int participantId = buffer.getInt(body + REPLACE_PARTICIPANT, WIRE);
    if (state == CLOSED) {
      feed.rejected(clientOrderId, participantId, STATE_NOT_PERMITTED);
      return;
    }
    final int resting = book.named(participantId, clientOrderId);
    if (resting == 0) {
      feed.rejected(clientOrderId, participantId, UNKNOWN_ORDER);
      return;
    }
    final long quantity = buffer.getLong(body + REPLACE_QUANTITY, WIRE);
    final long price = buffer.getLong(body + REPLACE_PRICE, WIRE);
    final long verdict = refusalOrTickForReplace(resting, quantity, price);
    if (verdict < 0) {
      feed.rejected(clientOrderId, participantId, reasonOf(verdict));
      return;
    }
    final int side = slab.side(resting);
    final int newTick = (int) verdict;
    final long remainder = quantity - slab.executed(resting);
    if (newTick == slab.tick(resting) && remainder < slab.remaining(resting)) {
      // (FR-4.4) Lowering quantity at the same price keeps queue position.
      final long shownBefore = slab.displayed(resting);
      final long remainingBefore = slab.remaining(resting);
      slab.reduceTo(resting, remainder);
      book.quantitiesChanged(
          side, resting, slab.displayed(resting) - shownBefore, remainder - remainingBefore);
      feed.reduced(slab.id(resting), slab.displayed(resting));
      reportIndicative();
      return;
    }
    book.remove(side, resting);
    feed.removed(slab.id(resting), slab.displayed(resting), REPLACED);
    // The same order under a new price or quantity, in a fresh slot so the old one can go back.
    // It keeps both ids and its display size, and loses queue position (FR-4.5, FR-4.8, FR-4.10).
    final long id = slab.id(resting);
    final int pricing = slab.pricing(resting);
    final int timeInForce = slab.timeInForce(resting);
    final boolean postOnly = slab.postOnly(resting);
    final long minQuantity = slab.minQuantity(resting);
    final long displaySize = slab.displaySize(resting);
    final long smpId = slab.smpId(resting);
    final long executed = slab.executed(resting);
    slab.release(resting);
    final int fresh = slab.acquire();
    slab.init(
        fresh,
        id,
        clientOrderId,
        participantId,
        side,
        pricing,
        timeInForce,
        postOnly,
        newTick,
        remainder,
        minQuantity,
        displaySize,
        0,
        smpId,
        ++arrival,
        executed);
    admit(fresh, side);
  }

  private void massCancel(final DirectBuffer buffer, final int body) {
    final long clientOrderId = buffer.getLong(body + MASS_CLIENT, WIRE);
    final int participantId = buffer.getInt(body + MASS_PARTICIPANT, WIRE);
    if (state == CLOSED) {
      feed.rejected(clientOrderId, participantId, STATE_NOT_PERMITTED);
      return;
    }
    gathered.clear();
    book.of(participantId, gathered);
    triggers.of(participantId, gathered);
    gathered.sortByArrival(slab);
    for (int at = 0; at < gathered.size(); at++) {
      final int slot = gathered.get(at);
      if (slab.stop(slot)) {
        triggers.remove(slot);
        feed.removed(slab.id(slot), slab.remaining(slot), MASS_CANCELLED);
      } else {
        book.remove(slab.side(slot), slot);
        feed.removed(slab.id(slot), slab.displayed(slot), MASS_CANCELLED);
      }
      slab.release(slot);
    }
    reportIndicative();
  }

  // Trading state -------------------------------------------------------------------------------

  private void changeState(final DirectBuffer buffer, final int body) {
    final int entering = buffer.getByte(body + SESSION_STATE);
    if (callPhase(state) && entering != state) {
      uncross();
    }
    state = entering;
    feed.stateChanged(state);
    indicativePrice = 0;
    indicativeQuantity = 0;
    if (callPhase(state)) {
      reportIndicative();
    }
  }

  private void uncross() {
    auction.uncross(book, reference);
    if (!auction.crosses()) {
      return;
    }
    final long price = auction.price();
    long left = auction.quantity();
    willing(BUY, price, buys);
    willing(SELL, price, sells);
    int sell = 0;
    for (int at = 0; at < buys.size(); at++) {
      final int buy = buys.get(at);
      // A filled order's slot goes back to the free list inside cross, and nothing reacquires it
      // before these reads, so its quantities still say what they said when it died.
      while (slab.remaining(buy) > 0 && left > 0 && sell < sells.size()) {
        final int resting = sells.get(sell);
        left -= cross(buy, resting, price, left);
        if (slab.remaining(resting) == 0) {
          sell++;
        }
      }
    }
    reference = price;
    lastExecuted = price;
    fireTriggers();
  }

  private long cross(final int buy, final int sell, final long price, final long left) {
    final long quantity = Math.min(Math.min(slab.displayed(buy), slab.displayed(sell)), left);
    final long buyShown = slab.displayed(buy);
    final long sellShown = slab.displayed(sell);
    final boolean buyReplenishes = slab.take(buy, quantity);
    final boolean sellReplenishes = slab.take(sell, quantity);
    feed.executed(nextExecutionId++, slab.id(buy), slab.id(sell), price, quantity);
    reveal(buy, BUY, buyReplenishes, buyShown, quantity);
    reveal(sell, SELL, sellReplenishes, sellShown, quantity);
    return quantity;
  }

  /** Hidden quantity is displayed before it executes, in an auction as elsewhere (FR-5.5). */
  private void reveal(
      final int slot,
      final int side,
      final boolean replenishes,
      final long shownBefore,
      final long quantity) {
    if (slab.remaining(slot) == 0) {
      book.quantitiesChanged(side, slot, -shownBefore, -quantity);
      book.remove(side, slot);
      slab.release(slot);
    } else if (replenishes) {
      slab.rest(slot, ++arrival);
      book.requeued(side, slot, slab.displayed(slot) - shownBefore, -quantity);
      feed.rested(slab.id(slot), side, book.priceOfTick(slab.tick(slot)), slab.displayed(slot));
    } else {
      book.quantitiesChanged(side, slot, slab.displayed(slot) - shownBefore, -quantity);
    }
  }

  /** Everyone on one side willing at the price, earliest first, into the caller's space. */
  private void willing(final int side, final long price, final IntScratch into) {
    into.clear();
    final int limit = book.willingLimitRank(side, price);
    for (int rank = book.firstRank(side); rank <= limit; rank = book.rankAfter(side, rank)) {
      for (int slot = book.headAtRank(side, rank); slot != 0; slot = slab.next(slot)) {
        into.add(slot);
      }
    }
    into.sortByArrival(slab);
  }

  private void reportIndicative() {
    if (!callPhase(state)) {
      return;
    }
    auction.uncross(book, reference);
    if (auction.price() == indicativePrice && auction.quantity() == indicativeQuantity) {
      return;
    }
    indicativePrice = auction.price();
    indicativeQuantity = auction.quantity();
    feed.indicative(indicativePrice, indicativeQuantity);
  }

  private static boolean callPhase(final int state) {
    return state == OPENING_AUCTION || state == CLOSING_AUCTION;
  }

  // Validation ----------------------------------------------------------------------------------

  /** A refusal encoded below zero, so one verdict carries either the reason or the tick. */
  private static long refusal(final int reason) {
    return -1 - (long) reason;
  }

  private static int reasonOf(final long verdict) {
    return (int) (-1 - verdict);
  }

  private long refusalOrTick(
      final int side,
      final int pricing,
      final int timeInForce,
      final boolean postOnly,
      final long price,
      final long quantity,
      final long minQuantity,
      final long displayQuantity,
      final long triggerPrice,
      final long smpId) {
    if (state == CLOSED) {
      return refusal(STATE_NOT_PERMITTED);
    }
    if (quantity <= 0) {
      return refusal(NON_POSITIVE_QUANTITY);
    }
    if (lotSize != 1 && quantity % lotSize != 0) {
      return refusal(LOT_VIOLATION);
    }
    if (minQuantity > quantity) {
      return refusal(MINIMUM_QUANTITY_ABOVE_ORDER);
    }
    if (displayQuantity > quantity) {
      return refusal(DISPLAY_QUANTITY_ABOVE_ORDER);
    }
    if (inconsistent(pricing, timeInForce, postOnly)) {
      return refusal(INVALID_FIELDS);
    }
    long tick = 0;
    if (pricing == LIMIT) {
      tick = tickOrRefusal(price);
      if (tick < 0) {
        return tick;
      }
      if (Math.abs(price - reference) > bandWidth) {
        return refusal(DYNAMIC_BAND_VIOLATION);
      }
    }
    if (triggerPrice != 0) {
      // A stop is placed away from where the market is, so the dynamic band does not apply.
      final long triggerTick = tickOrRefusal(triggerPrice);
      if (triggerTick < 0) {
        return triggerTick;
      }
    }
    final int fromTheBook =
        refusalFromTheBook(
            side,
            pricing,
            timeInForce,
            postOnly,
            quantity,
            minQuantity,
            smpId,
            triggerPrice,
            (int) tick);
    return fromTheBook >= 0 ? refusal(fromTheBook) : tick;
  }

  /**
   * The price's tick index, or the refusal that keeps it off the ladder, in one division: the
   * quotient that proves the price on tick (VR-2.2) is the index the ladder wants.
   */
  private long tickOrRefusal(final long price) {
    if (price <= 0) {
      return refusal(NON_POSITIVE_PRICE);
    }
    final long ticks = price / tickSize;
    if (ticks * tickSize != price) {
      return refusal(TICK_VIOLATION);
    }
    if (price < minPrice || price > maxPrice) {
      return refusal(STATIC_BAND_VIOLATION);
    }
    return ticks - baseTick;
  }

  private static boolean inconsistent(
      final int pricing, final int timeInForce, final boolean postOnly) {
    if (pricing == MARKET) {
      return postOnly || timeInForce <= DAY;
    }
    return postOnly && timeInForce >= IOC;
  }

  private int refusalFromTheBook(
      final int side,
      final int pricing,
      final int timeInForce,
      final boolean postOnly,
      final long quantity,
      final long minQuantity,
      final long smpId,
      final long triggerPrice,
      final int tick) {
    if (triggerPrice != 0 || state != CONTINUOUS) {
      if (triggerPrice == 0 && timeInForce == FOK) {
        return FILL_OR_KILL_UNFILLABLE;
      }
      if (triggerPrice == 0 && minQuantity > 0) {
        return MINIMUM_QUANTITY_NOT_MET;
      }
      return -1;
    }
    final int limitRank = pricing == MARKET ? book.marketLimit() : book.rankOf(side ^ 1, tick);
    if (postOnly && book.nextToTake(side, limitRank) != 0) {
      return WOULD_CROSS;
    }
    if (timeInForce == FOK || minQuantity > 0) {
      final long fillable = book.fillable(side, limitRank, smpId);
      if (timeInForce == FOK && fillable < quantity) {
        return FILL_OR_KILL_UNFILLABLE;
      }
      if (minQuantity > 0 && fillable < minQuantity) {
        return MINIMUM_QUANTITY_NOT_MET;
      }
    }
    return -1;
  }

  private long refusalOrTickForReplace(final int resting, final long quantity, final long price) {
    if (quantity <= 0) {
      return refusal(NON_POSITIVE_QUANTITY);
    }
    if (quantity <= slab.executed(resting)) {
      return refusal(QUANTITY_BELOW_EXECUTED);
    }
    if (lotSize != 1 && quantity % lotSize != 0) {
      return refusal(LOT_VIOLATION);
    }
    final long tick = tickOrRefusal(price);
    if (tick < 0) {
      return tick;
    }
    if (Math.abs(price - reference) > bandWidth) {
      return refusal(DYNAMIC_BAND_VIOLATION);
    }
    final int side = slab.side(resting);
    if (slab.postOnly(resting)
        && state == CONTINUOUS
        && book.nextToTake(side, book.rankOf(side ^ 1, (int) tick)) != 0) {
      // (FR-4.6) A replace refused by a liquidity flag leaves the original order resting.
      return refusal(WOULD_CROSS);
    }
    return tick;
  }
}
