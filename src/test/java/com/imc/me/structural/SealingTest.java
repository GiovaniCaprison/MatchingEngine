package com.imc.me.structural;

import static org.assertj.core.api.Assertions.assertThat;

import com.imc.me.book.OrderBook;
import com.imc.me.book.OrderBookReader;
import com.imc.me.book.OrderBookWriter;
import com.imc.me.event.result.AmendResult;
import com.imc.me.event.result.CancelResult;
import com.imc.me.event.result.SubmitResult;
import com.imc.me.support.Requirement;
import com.imc.me.support.TestTags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Sealing is used where it buys exhaustiveness and nowhere else (OOD-7).
 *
 * <p>This is a structural test rather than an ArchUnit rule because ArchUnit has no predicate for
 * sealedness; {@code Class.isSealed()} answers it directly. It exists to stop the two opposite
 * mistakes from creeping back: unsealing a result hierarchy (which silently turns a compile error
 * into a runtime surprise for every consumer that forgets a case) and sealing a book interface
 * (which taxes every future implementation for nothing).
 */
@Tag(TestTags.FAST)
@DisplayName("Structural | Sealing")
class SealingTest {

  @Test
  @Requirement("API-9.1")
  @DisplayName("API-9.1: outcome hierarchies are sealed so switches are exhaustive")
  void outcomes_are_sealed() {
    // This is what lets a caller `switch` over an outcome with no `default` arm and have the
    // compiler prove the switch is complete. Adding a new outcome then breaks every incomplete
    // consumer at compile time -- that is the feature, not a nuisance (OOD-6).
    assertThat(SubmitResult.class.isSealed()).isTrue();
    assertThat(AmendResult.class.isSealed()).isTrue();
    assertThat(CancelResult.class.isSealed()).isTrue();
  }

  @Test
  @Requirement("API-9.1")
  @DisplayName("API-9.1: book interfaces are open so implementations can be added freely")
  void book_interfaces_are_not_sealed() {
    // Nobody will ever switch over book implementations -- that would be a type test on the data
    // structure, which is exactly what the interface exists to avoid. ArrayOrderBook is planned,
    // and a `permits` clause would make it a three-file edit for zero benefit (OOD-7).
    assertThat(OrderBook.class.isSealed()).isFalse();
    assertThat(OrderBookReader.class.isSealed()).isFalse();
    assertThat(OrderBookWriter.class.isSealed()).isFalse();
  }
}
