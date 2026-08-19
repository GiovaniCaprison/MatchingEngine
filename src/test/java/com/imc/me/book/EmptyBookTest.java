package com.imc.me.book;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imc.me.domain.OrderSide;
import com.imc.me.domain.OrderType;
import com.imc.me.event.result.CancelResult;
import com.imc.me.event.result.NotFound;
import com.imc.me.matching.PriceTimeMatcher;
import com.imc.me.support.Requirement;
import com.imc.me.support.TestTags;
import com.imc.me.util.Seq;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * An empty book answers every query cleanly and corrupts nothing (VR-3.1).
 *
 * <p>Also pins the narrow contracts (OOD-16): where a precondition is documented rather than
 * checked, a test is what verifies it, because there is no runtime branch doing so.
 */
@Tag(TestTags.FAST)
@DisplayName("Book | Empty book")
class EmptyBookTest {

  private final OrderBook book = new TreeMapOrderBook(new PriceTimeMatcher());

  @Test
  @Requirement("FR-5.2")
  @DisplayName("FR-5.2: top of book on an empty side is empty, not null or a sentinel")
  void top_of_book_is_empty_on_both_sides() {
    assertThat(book.topOfBook(OrderSide.BUY).isEmpty()).isTrue();
    assertThat(book.topOfBook(OrderSide.SELL).isEmpty()).isTrue();
    assertThat(book.topOfBook(OrderSide.BUY).present()).isFalse();
  }

  @Test
  @Requirement("VR-3.1")
  @DisplayName("VR-3.1: depth on an empty side is an empty snapshot")
  void depth_is_empty() {
    assertThat(book.depth(OrderSide.BUY, 5).levels().isEmpty()).isTrue();
    assertThat(book.depth(OrderSide.SELL, 5).levels()).isEqualTo(Seq.empty());
  }

  @Test
  @Requirement("API-2.1")
  @DisplayName("API-2.1: cancelling on an empty book returns not-found, never throws")
  void cancel_on_empty_book_is_not_found() {
    final CancelResult result = book.cancel(42L);

    assertThat(result).isInstanceOf(NotFound.class);
    assertThat(((NotFound) result).orderId()).isEqualTo(42L);
  }

  @Test
  @Requirement("VR-3.1")
  @DisplayName("VR-3.1: bestLevel on an empty side fails per its documented precondition")
  void best_level_requires_a_non_empty_side() {
    final BookSide side = new TreeMapBookSide(OrderSide.BUY);
    assertThat(side.isEmpty()).isTrue();

    // Documented, not defended (OOD-16). This test IS the enforcement: an empty side has no best
    // price, so asking is a programming error rather than an outcome. A null-check here would be a
    // branch that always goes one way, per level per aggressing order, on the hottest path.
    assertThatThrownBy(side::bestLevel).isInstanceOf(NullPointerException.class);
  }

  @Test
  @Requirement("VR-3.1")
  @DisplayName("VR-3.1: an emptied side reports empty again and holds no orders")
  void side_emptied_by_removal_is_empty_again() {
    final BookSide side = new TreeMapBookSide(OrderSide.BUY);
    final Order order = Order.of(1L, 100L, 10L, OrderSide.BUY, OrderType.LIMIT);

    side.addOrder(order);
    assertThat(side.isEmpty()).isFalse();

    // VR-4.2: a side swept clean must be indistinguishable from one that was never used, or the
    // next order to arrive inherits a phantom level.
    side.remove(order);
    assertThat(side.isEmpty()).isTrue();
    assertThat(side.get(1L)).isNull();
  }
}
