package com.imc.me.book;

import static org.assertj.core.api.Assertions.assertThat;

import com.imc.me.domain.OrderSide;
import com.imc.me.domain.OrderType;
import com.imc.me.support.Requirement;
import com.imc.me.support.TestTags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Node-level regression tests for the intrusive FIFO list inside a price level.
 *
 * <p>This test lives in {@code com.imc.me.book} rather than under a test layer because the node
 * links it asserts on are package-private (OOD-1/OOD-4): only a same-package caller can see them.
 * That is the cost of compiler-enforced confinement, and it is worth paying — the alternative is
 * public mutators that any caller can use to corrupt the book.
 *
 * <p>These sit in the fast lane rather than the property lane on purpose: the corruption they pin
 * (a removed node keeping live links) is only reachable through a remove-then-re-add sequence, and
 * a randomised generator finds it slowly and reports it as a confusing aggregate mismatch.
 */
@Tag(TestTags.FAST)
@DisplayName("Book | Price level node linkage")
class PriceLevelNodeTest {

  private static Order order(final long id, final long qty) {
    return Order.of(id, 100L, qty, OrderSide.BUY, OrderType.LIMIT);
  }

  @Test
  @Requirement("VR-6.1")
  @DisplayName("VR-6.1: totalQty tracks the orders actually resting in the level")
  void total_qty_tracks_resting_orders() {
    final PriceLevel level = new LinkedListPriceLevel(100L);
    final Order first = order(1L, 10L);
    final Order second = order(2L, 7L);

    level.add(first);
    level.add(second);
    assertThat(level.totalQty()).isEqualTo(17L);

    level.fillFirst(order(99L, 4L), 4L);
    assertThat(level.totalQty()).isEqualTo(13L);

    level.remove(first);
    assertThat(level.totalQty()).isEqualTo(7L);

    level.remove(second);
    assertThat(level.totalQty()).isZero();
    assertThat(level.isEmpty()).isTrue();
  }

  @Test
  @Requirement("NFR-3.2")
  @DisplayName("NFR-3.2: a removed order is fully detached from the list")
  void removed_order_is_detached() {
    final PriceLevel level = new LinkedListPriceLevel(100L);
    final Order first = order(1L, 10L);
    final Order middle = order(2L, 10L);
    final Order last = order(3L, 10L);

    level.add(first);
    level.add(middle);
    level.add(last);
    level.remove(middle);

    assertThat(middle.next()).isNull();
    assertThat(middle.prev()).isNull();
    assertThat(first.next()).isSameAs(last);
    assertThat(last.prev()).isSameAs(first);
  }

  @Test
  @Requirement("NFR-3.2")
  @DisplayName("NFR-3.2: re-adding a removed order does not corrupt the list")
  void re_added_order_does_not_corrupt_the_list() {
    final PriceLevel level = new LinkedListPriceLevel(100L);
    final Order first = order(1L, 10L);
    final Order second = order(2L, 10L);

    level.add(first);
    level.add(second);

    // `second` leaves while it still has a predecessor, so a node that is not detached on
    // removal keeps a live `prev` pointing at `first`.
    level.remove(second);
    level.remove(first);
    assertThat(level.isEmpty()).isTrue();

    // Re-entering an empty level takes the head path, which never rewrites `prev`. A stale
    // `prev` therefore survives into a node that is now the head of the list.
    level.add(second);
    assertThat(level.first()).isSameAs(second);
    assertThat(second.prev()).isNull();

    // The corruption: removing the head follows the stale `prev`, so `head` is never advanced
    // and the level claims to hold an order forever, while writing into a detached node.
    level.remove(second);
    assertThat(level.isEmpty()).isTrue();
    assertThat(level.totalQty()).isZero();
    assertThat(first.next()).isNull();
  }

  @Test
  @Requirement("NFR-3.2")
  @DisplayName("NFR-3.2: moving an order between levels leaves both consistent")
  void order_moved_between_levels_leaves_both_consistent() {
    final PriceLevel from = new LinkedListPriceLevel(100L);
    final PriceLevel to = new LinkedListPriceLevel(101L);
    final Order stays = order(1L, 4L);
    final Order moves = order(2L, 6L);

    from.add(stays);
    from.add(moves);

    // A reprice amend (FR-4.4) unlinks from one level and appends to another; the node must
    // arrive carrying nothing from its old level or the two lists become cross-linked.
    from.remove(moves);
    to.add(moves);

    assertThat(from.totalQty()).isEqualTo(4L);
    assertThat(to.totalQty()).isEqualTo(6L);
    assertThat(from.first()).isSameAs(stays);
    assertThat(stays.next()).isNull();
    assertThat(to.first()).isSameAs(moves);
    assertThat(moves.prev()).isNull();
    assertThat(moves.next()).isNull();
  }

  @Test
  @Requirement("NFR-3.2")
  @DisplayName("NFR-3.2: emptying a level then reusing it leaves no residue")
  void emptied_level_can_be_reused() {
    final PriceLevel level = new LinkedListPriceLevel(100L);
    final Order only = order(1L, 5L);

    level.add(only);
    level.remove(only);
    assertThat(level.isEmpty()).isTrue();

    final Order next = order(2L, 3L);
    level.add(next);

    assertThat(level.first()).isSameAs(next);
    assertThat(next.prev()).isNull();
    assertThat(next.next()).isNull();
    assertThat(level.totalQty()).isEqualTo(3L);
  }
}
