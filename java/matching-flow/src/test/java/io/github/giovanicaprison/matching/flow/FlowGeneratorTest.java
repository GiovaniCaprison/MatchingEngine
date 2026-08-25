package io.github.giovanicaprison.matching.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.within;

import io.github.giovanicaprison.matching.protocol.CancelOrderDecoder;
import io.github.giovanicaprison.matching.protocol.InstrumentDefinitionDecoder;
import io.github.giovanicaprison.matching.protocol.MassCancelDecoder;
import io.github.giovanicaprison.matching.protocol.MessageHeaderDecoder;
import io.github.giovanicaprison.matching.protocol.NewOrderDecoder;
import io.github.giovanicaprison.matching.protocol.PricingInstruction;
import io.github.giovanicaprison.matching.protocol.ReplaceOrderDecoder;
import io.github.giovanicaprison.matching.protocol.SessionStateChangeDecoder;
import io.github.giovanicaprison.matching.protocol.Side;
import io.github.giovanicaprison.matching.protocol.TimeInForce;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A generated log is an input to everything else, so what it guarantees matters more than what it
 * happens to produce. Reproducibility first, then the properties a run depends on.
 */
class FlowGeneratorTest {

  private static final FlowParameters PARAMETERS = FlowParameters.standard(20_260_824L, 40_000);

  @Test
  @DisplayName("the regime changes where the shift says, and nowhere before")
  void the_shift_changes_the_mix() {
    final FlowParameters.Shift shift =
        new FlowParameters.Shift(
            20_000,
            FlowParameters.Composition.limitAndMarketOnly(),
            FlowParameters.Placement.standard());
    final CommandLog log =
        FlowGenerator.generate(
            new FlowParameters(
                7,
                40_000,
                5_000,
                FlowParameters.Instrument.standard(),
                FlowParameters.Composition.standard(),
                FlowParameters.Placement.standard(),
                0,
                shift));

    final MessageHeaderDecoder header = new MessageHeaderDecoder();
    final NewOrderDecoder decoder = new NewOrderDecoder();
    int qualifiedBefore = 0;
    int qualifiedAfter = 0;
    for (int command = log.measuredFrom(); command < log.count(); command++) {
      if (log.templateId(command) != NewOrderDecoder.TEMPLATE_ID) {
        continue;
      }
      header.wrap(log.buffer(), log.offset(command));
      decoder.wrap(
          log.buffer(),
          log.offset(command) + MessageHeaderDecoder.ENCODED_LENGTH,
          header.blockLength(),
          header.version());
      final boolean qualified =
          decoder.minQuantity() != 0
              || decoder.displayQuantity() != 0
              || decoder.triggerPrice() != 0
              || decoder.smpId() != 0
              || decoder.flags().postOnly();
      if (command - log.measuredFrom() < shift.atCommand()) {
        qualifiedBefore += qualified ? 1 : 0;
      } else {
        qualifiedAfter += qualified ? 1 : 0;
      }
    }
    assertThat(qualifiedBefore)
        .as("the standard regime uses the qualifiers, so some must appear before the seam")
        .isPositive();
    assertThat(qualifiedAfter)
        .as("after the seam the composition carries no qualifiers, so none may appear")
        .isZero();
  }

  @Test
  @DisplayName("the same seed produces the same bytes")
  void generation_is_reproducible() {
    final CommandLog first = FlowGenerator.generate(PARAMETERS);
    final CommandLog second = FlowGenerator.generate(PARAMETERS);

    assertThat(second.count()).isEqualTo(first.count());
    assertThat(bytes(second)).isEqualTo(bytes(first));
  }

  @Test
  @DisplayName("another seed produces another log")
  void the_seed_decides_the_flow() {
    final CommandLog other =
        FlowGenerator.generate(FlowParameters.standard(PARAMETERS.seed() + 1, 40_000));

    assertThat(bytes(other)).isNotEqualTo(bytes(FlowGenerator.generate(PARAMETERS)));
  }

  @Test
  @DisplayName("a log opens with its instrument and its session, then fills the book")
  void the_log_is_ready_to_replay() {
    final CommandLog log = FlowGenerator.generate(PARAMETERS);

    assertThat(log.templateId(0)).isEqualTo(InstrumentDefinitionDecoder.TEMPLATE_ID);
    assertThat(log.templateId(1)).isEqualTo(SessionStateChangeDecoder.TEMPLATE_ID);
    assertThat(log.measuredFrom()).isEqualTo(2 + PARAMETERS.restingOrders());
    assertThat(log.count()).isEqualTo(2 + PARAMETERS.restingOrders() + PARAMETERS.commands());
    for (int command = 2; command < log.measuredFrom(); command++) {
      assertThat(log.templateId(command)).isEqualTo(NewOrderDecoder.TEMPLATE_ID);
    }
  }

  @Test
  @DisplayName("every command is one the protocol defines and occupies exactly its own bytes")
  void every_command_is_well_formed() {
    final CommandLog log = FlowGenerator.generate(PARAMETERS);
    final MessageHeaderDecoder header = new MessageHeaderDecoder();
    int consumed = 0;

    for (int command = 0; command < log.count(); command++) {
      header.wrap(log.buffer(), log.offset(command));
      assertThat(header.schemaId()).isEqualTo(NewOrderDecoder.SCHEMA_ID);
      assertThat(log.templateId(command)).isIn(templateIds());
      assertThat(log.length(command))
          .isEqualTo(MessageHeaderDecoder.ENCODED_LENGTH + header.blockLength());
      assertThat(log.offset(command)).isEqualTo(consumed);
      consumed += log.length(command);
    }
  }

  @Test
  @DisplayName("a cancel or a replace names an order entered earlier, and never one twice")
  void targets_are_orders_the_flow_placed() {
    final CommandLog log = FlowGenerator.generate(PARAMETERS);
    final MessageHeaderDecoder header = new MessageHeaderDecoder();
    final CancelOrderDecoder cancel = new CancelOrderDecoder();
    final Set<Long> cancelled = new HashSet<>();
    int entered = 0;
    int targeted = 0;

    for (int command = 0; command < log.count(); command++) {
      if (log.templateId(command) == NewOrderDecoder.TEMPLATE_ID) {
        entered++;
        continue;
      }
      if (log.templateId(command) != CancelOrderDecoder.TEMPLATE_ID) {
        continue;
      }
      targeted++;
      header.wrap(log.buffer(), log.offset(command));
      cancel.wrap(
          log.buffer(),
          log.offset(command) + MessageHeaderDecoder.ENCODED_LENGTH,
          header.blockLength(),
          header.version());
      assertThat(cancel.clientOrderId()).isBetween(1L, (long) entered);
      assertThat(cancelled.add(cancel.clientOrderId()))
          .as("an order the generator has already cancelled is not a target again")
          .isTrue();
    }
    assertThat(targeted).isPositive();
  }

  @Test
  @DisplayName("the composition asked for is roughly the composition produced")
  void the_mix_follows_the_parameters() {
    final CommandLog log = FlowGenerator.generate(PARAMETERS);
    int cancels = 0;
    int replaces = 0;
    int orders = 0;

    for (int command = log.measuredFrom(); command < log.count(); command++) {
      switch (log.templateId(command)) {
        case CancelOrderDecoder.TEMPLATE_ID -> cancels++;
        case ReplaceOrderDecoder.TEMPLATE_ID -> replaces++;
        case NewOrderDecoder.TEMPLATE_ID -> orders++;
        default -> {}
      }
    }
    final double commands = PARAMETERS.commands();
    assertThat(cancels / commands).isCloseTo(PARAMETERS.composition().cancel(), within(0.005));
    assertThat(replaces / commands).isCloseTo(PARAMETERS.composition().replace(), within(0.005));
    assertThat(orders / commands)
        .isCloseTo(
            1
                - PARAMETERS.composition().cancel()
                - PARAMETERS.composition().replace()
                - PARAMETERS.composition().massCancel(),
            within(0.005));
  }

  @Test
  @DisplayName("orders sit on tick, on lot, and inside the band")
  void orders_are_valid_before_the_engine_sees_them() {
    final CommandLog log = FlowGenerator.generate(PARAMETERS);
    final FlowParameters.Instrument instrument = PARAMETERS.instrument();

    forEachOrder(
        log,
        order -> {
          assertThat(order.quantity() % instrument.lotSize()).isZero();
          assertThat(order.displayQuantity()).isLessThan(order.quantity());
          if (order.pricing() == PricingInstruction.LIMIT) {
            assertThat(order.price() % instrument.tickSize()).isZero();
            assertThat(Math.abs(order.price() - instrument.openingReference()))
                .isLessThanOrEqualTo(instrument.bandWidth());
          }
        });
  }

  @Test
  @DisplayName("the qualifiers a venue puts only on a crossing order are only on one")
  void qualifiers_go_where_a_venue_puts_them() {
    final CommandLog log = FlowGenerator.generate(PARAMETERS);

    forEachOrder(
        log,
        order -> {
          if (order.minQuantity() != 0) {
            assertThat(crosses(order))
                .as("a minimum quantity on a resting order is refused")
                .isTrue();
          }
          if (order.timeInForce() != TimeInForce.GOOD_TILL_CANCEL) {
            assertThat(crosses(order)).as("nobody sends a passive immediate-or-cancel").isTrue();
          }
        });
  }

  @Test
  @DisplayName("a flow that would reach outside the band is refused")
  void an_impossible_placement_is_refused() {
    final FlowParameters.Instrument instrument = FlowParameters.Instrument.standard();
    final FlowParameters parameters =
        new FlowParameters(
            1,
            100,
            0,
            instrument,
            FlowParameters.Composition.standard(),
            new FlowParameters.Placement(1_000, 10, 4),
            0);

    assertThatIllegalArgumentException()
        .isThrownBy(() -> FlowGenerator.generate(parameters))
        .withMessageContaining("outside a band");
  }

  private static boolean crosses(final NewOrderDecoder order) {
    if (order.pricing() == PricingInstruction.MARKET) {
      return true;
    }
    final long reference = PARAMETERS.instrument().openingReference();
    return order.side() == Side.BUY ? order.price() > reference : order.price() < reference;
  }

  private static void forEachOrder(final CommandLog log, final Consumer<NewOrderDecoder> check) {
    final MessageHeaderDecoder header = new MessageHeaderDecoder();
    final NewOrderDecoder order = new NewOrderDecoder();
    for (int command = 0; command < log.count(); command++) {
      if (log.templateId(command) != NewOrderDecoder.TEMPLATE_ID) {
        continue;
      }
      header.wrap(log.buffer(), log.offset(command));
      order.wrap(
          log.buffer(),
          log.offset(command) + MessageHeaderDecoder.ENCODED_LENGTH,
          header.blockLength(),
          header.version());
      check.accept(order);
    }
  }

  private static List<Integer> templateIds() {
    return List.of(
        InstrumentDefinitionDecoder.TEMPLATE_ID,
        SessionStateChangeDecoder.TEMPLATE_ID,
        NewOrderDecoder.TEMPLATE_ID,
        CancelOrderDecoder.TEMPLATE_ID,
        ReplaceOrderDecoder.TEMPLATE_ID,
        MassCancelDecoder.TEMPLATE_ID);
  }

  private static byte[] bytes(final CommandLog log) {
    int total = 0;
    for (int command = 0; command < log.count(); command++) {
      total += log.length(command);
    }
    final byte[] bytes = new byte[total];
    int at = 0;
    for (int command = 0; command < log.count(); command++) {
      log.buffer().getBytes(log.offset(command), bytes, at, log.length(command));
      at += log.length(command);
    }
    return bytes;
  }
}
