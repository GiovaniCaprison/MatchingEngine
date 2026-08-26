package io.github.giovanicaprison.matching.calibration;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Measures one instrument's flow out of an ITCH session, so the generator's parameters are found
 * rather than chosen.
 *
 * <p>Reads the feed on standard input, which is how a multi-gigabyte session is handled without
 * storing it: the file streams through and this stops when it has seen enough.
 *
 * <p>What the feed shows and what it does not is the whole difficulty. A delete, a partial cancel,
 * a replace and an execution are all there to be counted. An aggressive order is not: one that
 * filled completely never appears, because nothing ever rested. Neither does an iceberg, a stop, a
 * post-only or a minimum quantity, since none of them is a field in a market data feed. So this
 * measures what is observable and says which parameters it cannot speak to.
 */
public final class Calibration {

  private static final int LOTS = 100;

  private final String stock;
  private final long limit;
  private final long from;
  private final long to;

  private final Map<Long, long[]> live = new HashMap<>();
  private final TreeMap<Long, Long> bids = new TreeMap<>();
  private final TreeMap<Long, Long> asks = new TreeMap<>();
  private final List<Long> sizes = new ArrayList<>();
  private final List<Long> depths = new ArrayList<>();
  private final List<Long> lifetimes = new ArrayList<>();
  private final Map<Character, Long> census = new TreeMap<>();

  private int locate = -1;
  private long read;
  private long adds;
  private long deletes;
  private long partialCancels;
  private long replaces;
  private long executions;
  private long hiddenTrades;
  private long crosses;
  private long addedShares;
  private long executedShares;
  private long hiddenShares;
  private long firstTimestamp;
  private long lastTimestamp;

  private Calibration(final String stock, final long limit, final long from, final long to) {
    this.stock = stock;
    this.limit = limit;
    this.from = from;
    this.to = to;
  }

  public static void main(final String[] arguments) throws IOException {
    String stock = "AAPL";
    long limit = Long.MAX_VALUE;
    // Continuous trading, which is what the generator models. Pre-market is thin and an opening
    // auction is a different animal, so counting either would describe a session nobody trades.
    long from = 9 * 3600 + 30 * 60;
    long to = 16 * 3600;
    for (int at = 0; at + 1 < arguments.length; at += 2) {
      switch (arguments[at]) {
        case "--stock" -> stock = arguments[at + 1];
        case "--messages" -> limit = Long.parseLong(arguments[at + 1]);
        case "--from" -> from = Long.parseLong(arguments[at + 1]);
        case "--to" -> to = Long.parseLong(arguments[at + 1]);
        default -> throw new IllegalArgumentException(arguments[at] + " is not an argument");
      }
    }
    final Calibration calibration = new Calibration(stock, limit, from, to);
    calibration.consume(new BufferedInputStream(System.in, 1 << 20));
    calibration.report();
  }

  private void consume(final BufferedInputStream stream) throws IOException {
    final Itch itch = new Itch(stream);
    Itch.Message message;
    while ((message = itch.next()) != null && read < limit) {
      read++;
      if (message.type == 'R') {
        if (stock.equals(message.stock)) {
          locate = message.stockLocate;
        }
        continue;
      }
      if (message.stockLocate != locate) {
        continue;
      }
      final long seconds = message.timestamp / 1_000_000_000L;
      if (seconds < from || seconds > to) {
        // Still tracked, because an order placed before the open can be cancelled after it, and a
        // book that started mid session would put every early order at an unknown depth.
        applyQuietly(message);
        continue;
      }
      if (firstTimestamp == 0) {
        firstTimestamp = message.timestamp;
      }
      lastTimestamp = message.timestamp;
      census.merge(message.type, 1L, Long::sum);
      apply(message);
    }
  }

  /** Keeps the book right without counting anything, for the part of the day not being measured. */
  private void applyQuietly(final Itch.Message message) {
    final long addsWas = adds;
    final long deletesWas = deletes;
    final long cancelsWas = partialCancels;
    final long replacesWas = replaces;
    final long executionsWas = executions;
    final long hiddenWas = hiddenTrades;
    final long crossesWas = crosses;
    final long addedWas = addedShares;
    final long executedWas = executedShares;
    final long hiddenSharesWas = hiddenShares;
    final int sizesWas = sizes.size();
    final int depthsWas = depths.size();
    final int lifetimesWas = lifetimes.size();
    apply(message);
    adds = addsWas;
    deletes = deletesWas;
    partialCancels = cancelsWas;
    replaces = replacesWas;
    executions = executionsWas;
    hiddenTrades = hiddenWas;
    crosses = crossesWas;
    addedShares = addedWas;
    executedShares = executedWas;
    hiddenShares = hiddenSharesWas;
    trim(sizes, sizesWas);
    trim(depths, depthsWas);
    trim(lifetimes, lifetimesWas);
  }

  private static void trim(final List<Long> values, final int to) {
    while (values.size() > to) {
      values.removeLast();
    }
  }

  private void apply(final Itch.Message message) {
    switch (message.type) {
      case 'A', 'F' -> {
        adds++;
        addedShares += message.shares;
        sizes.add(message.shares);
        depths.add(distanceFromTheTouch(message.side, message.price));
        live.put(
            message.orderReference,
            new long[] {message.price, message.shares, message.side, message.timestamp});
        rest(message.side, message.price, message.shares);
      }
      case 'E', 'C' -> {
        executions++;
        executedShares += message.shares;
        reduce(message.orderReference, message.shares, message.timestamp, false);
      }
      case 'X' -> {
        // A partial cancel, which is a reduction that keeps queue position.
        partialCancels++;
        reduce(message.orderReference, message.shares, message.timestamp, false);
      }
      case 'D' -> {
        deletes++;
        final long[] order = live.get(message.orderReference);
        if (order != null) {
          reduce(message.orderReference, order[1], message.timestamp, true);
        }
      }
      case 'U' -> {
        replaces++;
        final long[] order = live.remove(message.orderReference);
        if (order != null) {
          take(order[2], order[0], order[1]);
          live.put(
              message.newOrderReference,
              new long[] {message.price, message.shares, order[2], order[3]});
          rest((char) order[2], message.price, message.shares);
        }
      }
      case 'P' -> {
        // A trade the visible book never showed, which is hidden liquidity reaching the tape.
        hiddenTrades++;
        hiddenShares += message.shares;
      }
      case 'Q' -> crosses++;
      default -> {}
    }
  }

  /** How far from the best price on the other side an order was placed, in cents. */
  private long distanceFromTheTouch(final char side, final long price) {
    final Long best =
        side == 'B'
            ? asks.isEmpty() ? null : asks.firstKey()
            : bids.isEmpty() ? null : bids.lastKey();
    if (best == null) {
      return -1;
    }
    return Math.abs(price - best) / 100;
  }

  private void rest(final char side, final long price, final long shares) {
    levels(side).merge(price, shares, Long::sum);
  }

  private void take(final long side, final long price, final long shares) {
    final NavigableMap<Long, Long> levels = levels((char) side);
    final Long standing = levels.get(price);
    if (standing == null) {
      return;
    }
    if (standing <= shares) {
      levels.remove(price);
    } else {
      levels.put(price, standing - shares);
    }
  }

  private void reduce(
      final long reference, final long shares, final long timestamp, final boolean whole) {
    final long[] order = live.get(reference);
    if (order == null) {
      return;
    }
    take(order[2], order[0], shares);
    order[1] -= shares;
    if (whole || order[1] <= 0) {
      live.remove(reference);
      lifetimes.add(timestamp - order[3]);
    }
  }

  private NavigableMap<Long, Long> levels(final char side) {
    return side == 'B' ? bids : asks;
  }

  private void report() {
    final long commands = adds + deletes + partialCancels + replaces;
    System.out.printf("stock                %s (locate %d)%n", stock, locate);
    System.out.printf("messages read        %,d%n", read);
    System.out.printf("session covered      %s%n", window());
    System.out.println();
    System.out.printf("commands             %,d%n", commands);
    System.out.printf("  adds               %,d  (%.4f)%n", adds, share(adds, commands));
    System.out.printf("  deletes            %,d  (%.4f)%n", deletes, share(deletes, commands));
    System.out.printf(
        "  partial cancels    %,d  (%.4f)%n", partialCancels, share(partialCancels, commands));
    System.out.printf("  replaces           %,d  (%.4f)%n", replaces, share(replaces, commands));
    System.out.println();
    System.out.printf("executions           %,d%n", executions);
    System.out.printf("hidden trades        %,d%n", hiddenTrades);
    System.out.printf("crosses              %,d%n", crosses);
    System.out.println();
    System.out.printf("shares added         %,d%n", addedShares);
    System.out.printf(
        "shares executed      %,d  (%.4f of added)%n",
        executedShares, share(executedShares, addedShares));
    System.out.printf(
        "shares hidden        %,d  (%.4f of executed)%n",
        hiddenShares, share(hiddenShares, executedShares));
    System.out.println();
    report("order size, shares", sizes);
    report("order size, round lots", sizes.stream().map(size -> size / LOTS).toList());
    report("placement depth, cents", depths.stream().filter(depth -> depth >= 0).toList());
    report("lifetime, milliseconds", lifetimes.stream().map(nanos -> nanos / 1_000_000).toList());
    System.out.println();
    audit();
    System.out.println();
    System.out.println("not observable in a market data feed, so chosen rather than measured:");
    System.out.println("  aggressive, market, iceberg, stop, immediateOrCancel, fillOrKill,");
    System.out.println("  postOnly, minimumQuantity, selfMatch");
  }

  /**
   * Every message type this session used for the instrument, against what our own protocol calls
   * it.
   *
   * <p>The point is the gaps. A real venue's feed is the strongest available check on whether our
   * nine events cover an instrument's life, and a type with nothing beside it is either something
   * the boundary deliberately leaves out or something missing from it. Reading the specification
   * cannot settle which, because a specification does not say what a Tuesday actually contains.
   */
  private void audit() {
    System.out.println("message types seen, and what this protocol calls them:");
    census.forEach(
        (type, count) ->
            System.out.printf("  %c  %,12d   %s%n", type, count, OURS.getOrDefault(type, GAP)));
  }

  private static final String GAP = "nothing, see below";

  private static final Map<Character, String> OURS = ours();

  private static Map<Character, String> ours() {
    final Map<Character, String> named = new LinkedHashMap<>();
    named.put('S', "SessionStateChanged");
    named.put('R', "InstrumentDefinition");
    named.put('H', "SessionStateChanged, a halt");
    named.put('h', "SessionStateChanged, a halt");
    named.put('A', "OrderRested");
    named.put('F', "OrderRested");
    named.put('E', "OrderExecuted");
    named.put('C', "OrderExecuted");
    named.put('X', "OrderReduced");
    named.put('D', "OrderRemoved");
    named.put('U', "OrderRemoved then OrderRested");
    named.put('Q', "OrderExecuted, from an uncrossing");
    named.put('I', "AuctionIndicative");
    named.put('J', "the instrument's price bands");
    return Map.copyOf(named);
  }

  private String window() {
    if (firstTimestamp == 0) {
      return "nothing for this stock";
    }
    return "%s to %s".formatted(clock(firstTimestamp), clock(lastTimestamp));
  }

  private static String clock(final long nanos) {
    final long seconds = nanos / 1_000_000_000L;
    return "%02d:%02d:%02d".formatted(seconds / 3600, seconds / 60 % 60, seconds % 60);
  }

  private static double share(final long part, final long whole) {
    return whole == 0 ? 0 : (double) part / whole;
  }

  private static void report(final String name, final List<Long> values) {
    if (values.isEmpty()) {
      System.out.printf("%-24s nothing%n", name);
      return;
    }
    final List<Long> sorted = values.stream().sorted().toList();
    System.out.printf(
        "%-24s p50 %,d   p90 %,d   p99 %,d   mean %,d   max %,d%n",
        name,
        at(sorted, 50),
        at(sorted, 90),
        at(sorted, 99),
        Math.round(values.stream().mapToLong(Long::longValue).average().orElse(0)),
        sorted.getLast());
  }

  private static long at(final List<Long> sorted, final int percentile) {
    return sorted.get(Math.min(sorted.size() - 1, (int) ((long) percentile * sorted.size() / 100)));
  }
}
