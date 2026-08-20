package com.imc.me.support;

import static org.assertj.core.api.Assertions.fail;

import com.imc.me.MatchingEngine;
import com.imc.me.domain.Instrument;
import com.imc.me.domain.OrderSide;
import com.imc.me.domain.OrderType;
import com.imc.me.event.command.NewOrder;
import com.imc.me.event.dto.Depth;
import com.imc.me.event.result.Accepted;
import com.imc.me.event.result.AmendOutcome;
import com.imc.me.event.result.CancelResult;
import com.imc.me.event.result.Cancelled;
import com.imc.me.event.result.Rejected;
import com.imc.me.event.result.SubmitResult;
import com.imc.me.event.sink.EngineListener;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Replays a scenario through the engine and diffs its output against the blessed file.
 *
 * <p>The grammar is specified in {@code docs/SCENARIO_FORMAT.md}, which is the contract a second
 * implementation would be checked against. Everything here goes through the public API, so a
 * scenario cannot accidentally depend on an internal and stays valid across a rewrite.
 */
public final class ScenarioRunner {

  /** How deep the final book is reported. A fixture needing more is out of scope for the format. */
  private static final int MAX_BOOK_LEVELS = 100;

  private static final Instrument DEFAULT_INSTRUMENT = new Instrument(1, "TEST", 1L, 1L, 4);

  private ScenarioRunner() {}

  /**
   * The output a scenario produces, without comparing it to anything.
   *
   * <p>Package-private, and here so a fixture can be regenerated after a deliberate behaviour
   * change rather than retyped. Read the diff before blessing what it produces.
   */
  static List<String> render(final Path inputFile) throws IOException {
    return replay(directives(Files.readAllLines(inputFile)));
  }

  public static void run(final Path inputFile) throws IOException {
    final Path expectedFile =
        inputFile.resolveSibling(
            inputFile.getFileName().toString().replaceFirst("\\.input$", ".expected"));

    final List<String> input = directives(Files.readAllLines(inputFile));
    final List<String> expected = directives(Files.readAllLines(expectedFile));
    final List<String> actual = replay(input);

    compare(inputFile.getFileName().toString(), expected, actual);
  }

  /**
   * Drops comment and blank lines, so either file can be annotated freely.
   *
   * <p>A comment is a line whose first non-blank character is {@code #}. There are no trailing
   * comments, because {@code #} is also the order reference sigil and one character cannot be both.
   */
  private static List<String> directives(final List<String> lines) {
    final List<String> kept = new ArrayList<>();
    for (final String line : lines) {
      final String stripped = line.strip();
      if (stripped.isEmpty() || stripped.startsWith("#")) continue;
      kept.add(stripped.replaceAll("\\s+", " "));
    }
    return kept;
  }

  private static List<String> replay(final List<String> input) {
    final List<String> out = new ArrayList<>();
    final Recorder recorder = new Recorder();

    int cursor = 0;
    Instrument instrument = DEFAULT_INSTRUMENT;
    if (!input.isEmpty() && input.get(0).startsWith("INSTRUMENT")) {
      instrument = instrument(input.get(0));
      cursor = 1;
    }

    final MatchingEngine engine = new MatchingEngine(instrument);
    engine.register(recorder);

    final Map<Long, Integer> refByUid = new HashMap<>();
    final Map<Integer, Long> uidByRef = new HashMap<>();
    int newCount = 0;

    for (int i = cursor; i < input.size(); i++) {
      final String[] f = input.get(i).split(" ");
      recorder.reset();

      switch (f[0]) {
        case "NEW" -> {
          final int ref = ++newCount;
          final SubmitResult result =
              engine.submit(
                  new NewOrder(ref, side(f[1]), type(f[2]), Long.parseLong(f[4]), price(f[3])));

          final long uid =
              (result instanceof Accepted a) ? a.orderId() : ((Rejected) result).orderId();
          refByUid.put(uid, ref);
          uidByRef.put(ref, uid);

          out.addAll(recorder.tradeLines(refByUid));
          out.add(
              result instanceof Accepted a
                  ? "ACCEPTED #" + ref + " " + a.outcome()
                  : "REJECTED #" + ref + " " + ((Rejected) result).reason());
        }
        case "CANCEL" -> {
          final int ref = ref(f[1]);
          final CancelResult result = engine.cancel(uidByRef.getOrDefault(ref, -1L));
          out.add((result instanceof Cancelled ? "CANCELLED #" : "NOTFOUND #") + ref);
        }
        case "AMEND" -> {
          final int ref = ref(f[1]);
          final AmendOutcome outcome =
              engine.amend(
                  uidByRef.getOrDefault(ref, -1L), Long.parseLong(f[2]), Long.parseLong(f[3]));

          out.addAll(recorder.tradeLines(refByUid));
          out.add(
              outcome == AmendOutcome.NOT_FOUND
                  ? "NOTFOUND #" + ref
                  : "AMENDED #" + ref + " " + outcome);
        }
        default -> throw new IllegalArgumentException("unknown directive: " + input.get(i));
      }
    }

    out.addAll(bookLines(engine, OrderSide.BUY, "BID"));
    out.addAll(bookLines(engine, OrderSide.SELL, "ASK"));
    return out;
  }

  private static List<String> bookLines(
      final MatchingEngine engine, final OrderSide side, final String label) {
    final Depth depth = engine.depth(side, MAX_BOOK_LEVELS);
    final List<String> lines = new ArrayList<>();
    if (depth.levels().isEmpty()) {
      lines.add("BOOK " + label + " empty");
      return lines;
    }
    for (final Depth.Level level : depth.levels()) {
      lines.add("BOOK " + label + " " + level.price() + " qty=" + level.qty());
    }
    return lines;
  }

  /**
   * Captures the outbound trade stream for one command.
   *
   * <p>Trades are buffered rather than formatted as they arrive because the aggressor's engine id
   * is not known until the command returns, and the output names orders by their reference in the
   * fixture rather than by engine id.
   */
  private static final class Recorder implements EngineListener {
    private final List<long[]> trades = new ArrayList<>();

    private void reset() {
      trades.clear();
    }

    @Override
    public void onTrade(
        final long sequence,
        final long aggressorId,
        final long restingId,
        final long price,
        final long qty) {
      trades.add(new long[] {sequence, aggressorId, restingId, price, qty});
    }

    private List<String> tradeLines(final Map<Long, Integer> refByUid) {
      final List<String> lines = new ArrayList<>();
      for (final long[] t : trades) {
        lines.add(
            "TRADE seq="
                + t[0]
                + " aggressor="
                + ref(refByUid, t[1])
                + " resting="
                + ref(refByUid, t[2])
                + " price="
                + t[3]
                + " qty="
                + t[4]);
      }
      return lines;
    }

    private static String ref(final Map<Long, Integer> refByUid, final long uid) {
      final Integer ref = refByUid.get(uid);
      return ref == null ? "uid:" + uid : "#" + ref;
    }
  }

  private static Instrument instrument(final String line) {
    long tick = 1L;
    long lot = 1L;
    int scale = 4;
    for (final String field : line.split(" ")) {
      final int eq = field.indexOf('=');
      if (eq < 0) continue;
      final String key = field.substring(0, eq);
      final String value = field.substring(eq + 1);
      switch (key) {
        case "tick" -> tick = Long.parseLong(value);
        case "lot" -> lot = Long.parseLong(value);
        case "scale" -> scale = Integer.parseInt(value);
        default -> throw new IllegalArgumentException("unknown instrument field: " + key);
      }
    }
    return new Instrument(1, "TEST", tick, lot, scale);
  }

  private static OrderSide side(final String token) {
    return OrderSide.valueOf(token);
  }

  private static OrderType type(final String token) {
    return OrderType.valueOf(token);
  }

  /** A market order has no price of its own, written {@code -} in a fixture. */
  private static long price(final String token) {
    return "-".equals(token) ? 0L : Long.parseLong(token);
  }

  private static int ref(final String token) {
    return Integer.parseInt(token.startsWith("#") ? token.substring(1) : token);
  }

  /**
   * Reports the first line that differs, then the whole actual output.
   *
   * <p>Printing all of it is what makes re-blessing a review rather than a guess: the reviewer sees
   * the new output in full and can paste it in once they believe it.
   */
  private static void compare(
      final String name, final List<String> expected, final List<String> actual) {
    final int limit = expected.size() > actual.size() ? expected.size() : actual.size();
    for (int i = 0; i < limit; i++) {
      final String e = i < expected.size() ? expected.get(i) : "<nothing>";
      final String a = i < actual.size() ? actual.get(i) : "<nothing>";
      if (!e.equals(a)) {
        fail(
            """
            %s differs at line %d

              expected: %s
                actual: %s

            --- full actual output ---
            %s
            --------------------------
            """
                .formatted(name, i + 1, e, a, String.join("\n", actual)));
      }
    }
  }
}
