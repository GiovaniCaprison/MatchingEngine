// The pooled rung's book with nothing on it the lean remit does not need: the same hand-built
// level tree, intrusive queues and open-addressing name index the full rung holds, so the
// comparison between the two isolates the feature set at this layout and not a structural
// difference. What is missing is the feature machinery: no pro-rata level walk, no fillable
// pre-check, no re-queueing, because nothing here replenishes. And like the rung it shadows, the
// steady state allocates nothing (NFR-4.3).

#pragma once

#include <algorithm>
#include <cstdint>
#include <deque>
#include <vector>

#include "lean-pooled/order.hpp"

namespace io::github::giovanicaprison::matching::lean::pooled {

class Book {
 public:
  // One price: its queue, the total it holds, and its own place in its side's tree.
  struct Level {
    std::int64_t key = 0;
    Level* left = nullptr;
    Level* right = nullptr;
    int height = 0;

    std::int64_t price = 0;
    OrderPtr head = nullptr;
    OrderPtr tail = nullptr;
    std::int64_t displayed = 0;
  };

  Book() {
    levels_.resize(4096);
    for (Level& level : levels_) {
      level.left = freeLevels_;
      freeLevels_ = &level;
    }
    allocateNames(1 << 16);
  }

  void add(const OrderPtr order) {
    Level& level = levelFor(order->side(), order->price());
    append(level, order);
    level.displayed += order->remaining();
    namePut(order);
  }

  void remove(const OrderPtr order) {
    const std::int64_t key = keyOf(order->side(), order->price());
    Level& level = *this->level(order->side(), key);
    unlink(level, order);
    level.displayed -= order->remaining();
    if (level.head == nullptr) {
      deleteLevel(order->side(), key);
    }
    nameRemove(order->participantId(), order->clientOrderId());
  }

  // The order's remaining quantity changed in place, so the level's total follows.
  void displayedChanged(const OrderPtr order, const std::int64_t before) {
    level(order->side(), keyOf(order->side(), order->price()))->displayed +=
        order->remaining() - before;
  }

  OrderPtr named(const std::uint32_t participantId, const std::uint64_t clientOrderId) const {
    std::size_t at = slotOf(participantId, clientOrderId);
    while (nameOrders_[at] != nullptr) {
      if (nameParticipants_[at] == participantId && nameClients_[at] == clientOrderId) {
        return nameOrders_[at];
      }
      at = (at + 1) & nameMask_;
    }
    return nullptr;
  }

  // The order a taker reaches next: the front of the best crossing level (FR-3.1, FR-3.3).
  OrderPtr nextToTake(const protocol::Side::Value takerSide, const std::int64_t limit) const {
    const Level* const best = this->best(opposite(takerSide));
    if (best == nullptr || !crosses(takerSide, limit, best->price)) {
      return nullptr;
    }
    return best->head;
  }

  // Every order for one participant appended to the caller's space. Still a walk, on purpose.
  void of(const std::uint32_t participantId, std::vector<OrderPtr>& into) const {
    for (std::size_t at = 0; at <= nameMask_; at++) {
      const OrderPtr order = nameOrders_[at];
      if (order != nullptr && order->participantId() == participantId) {
        into.push_back(order);
      }
    }
  }

  static protocol::Side::Value opposite(const protocol::Side::Value side) {
    return side == protocol::Side::BUY ? protocol::Side::SELL : protocol::Side::BUY;
  }

  static bool crosses(const protocol::Side::Value takerSide, const std::int64_t limit,
                      const std::int64_t price) {
    if (limit == 0) {
      return true;
    }
    return takerSide == protocol::Side::BUY ? price <= limit : price >= limit;
  }

 private:
  // The level tree ------------------------------------------------------------------------------

  static std::int64_t keyOf(const protocol::Side::Value side, const std::int64_t price) {
    return side == protocol::Side::BUY ? -price : price;
  }

  Level* best(const protocol::Side::Value side) const {
    Level* node = side == protocol::Side::BUY ? bids_ : asks_;
    if (node == nullptr) {
      return nullptr;
    }
    while (node->left != nullptr) {
      node = node->left;
    }
    return node;
  }

  Level* level(const protocol::Side::Value side, const std::int64_t key) const {
    Level* node = side == protocol::Side::BUY ? bids_ : asks_;
    while (node != nullptr && node->key != key) {
      node = key < node->key ? node->left : node->right;
    }
    return node;
  }

  Level& levelFor(const protocol::Side::Value side, const std::int64_t price) {
    const std::int64_t key = keyOf(side, price);
    if (side == protocol::Side::BUY) {
      bids_ = insert(bids_, key, price);
    } else {
      asks_ = insert(asks_, key, price);
    }
    return *found_;
  }

  void deleteLevel(const protocol::Side::Value side, const std::int64_t key) {
    if (side == protocol::Side::BUY) {
      bids_ = erase(bids_, key);
    } else {
      asks_ = erase(asks_, key);
    }
  }

  Level* insert(Level* const node, const std::int64_t key, const std::int64_t price) {
    if (node == nullptr) {
      found_ = acquireLevel(key, price);
      return found_;
    }
    if (key < node->key) {
      node->left = insert(node->left, key, price);
    } else if (key > node->key) {
      node->right = insert(node->right, key, price);
    } else {
      found_ = node;
      return node;
    }
    return balanced(node);
  }

  Level* erase(Level* const node, const std::int64_t key) {
    if (key < node->key) {
      node->left = erase(node->left, key);
    } else if (key > node->key) {
      node->right = erase(node->right, key);
    } else {
      if (node->left == nullptr || node->right == nullptr) {
        Level* const child = node->left != nullptr ? node->left : node->right;
        releaseLevel(node);
        return child;
      }
      Level* successor = node->right;
      while (successor->left != nullptr) {
        successor = successor->left;
      }
      node->key = successor->key;
      node->price = successor->price;
      node->head = successor->head;
      node->tail = successor->tail;
      node->displayed = successor->displayed;
      node->right = eraseLeftmost(node->right);
    }
    return balanced(node);
  }

  Level* eraseLeftmost(Level* const node) {
    if (node->left == nullptr) {
      Level* const child = node->right;
      releaseLevel(node);
      return child;
    }
    node->left = eraseLeftmost(node->left);
    return balanced(node);
  }

  static int heightOf(const Level* const node) { return node == nullptr ? 0 : node->height; }

  static void measure(Level* const node) {
    node->height = 1 + std::max(heightOf(node->left), heightOf(node->right));
  }

  static Level* balanced(Level* const node) {
    measure(node);
    const int lean = heightOf(node->left) - heightOf(node->right);
    if (lean > 1) {
      if (heightOf(node->left->left) < heightOf(node->left->right)) {
        node->left = rotateLeft(node->left);
      }
      return rotateRight(node);
    }
    if (lean < -1) {
      if (heightOf(node->right->right) < heightOf(node->right->left)) {
        node->right = rotateRight(node->right);
      }
      return rotateLeft(node);
    }
    return node;
  }

  static Level* rotateLeft(Level* const node) {
    Level* const pivot = node->right;
    node->right = pivot->left;
    pivot->left = node;
    measure(node);
    measure(pivot);
    return pivot;
  }

  static Level* rotateRight(Level* const node) {
    Level* const pivot = node->left;
    node->left = pivot->right;
    pivot->right = node;
    measure(node);
    measure(pivot);
    return pivot;
  }

  Level* acquireLevel(const std::int64_t key, const std::int64_t price) {
    Level* level = freeLevels_;
    if (level == nullptr) {
      levels_.emplace_back();
      level = &levels_.back();
    } else {
      freeLevels_ = level->left;
    }
    level->key = key;
    level->price = price;
    level->left = nullptr;
    level->right = nullptr;
    level->height = 1;
    level->head = nullptr;
    level->tail = nullptr;
    level->displayed = 0;
    return level;
  }

  void releaseLevel(Level* const level) {
    level->left = freeLevels_;
    level->right = nullptr;
    level->head = nullptr;
    level->tail = nullptr;
    freeLevels_ = level;
  }

  // The intrusive queue -------------------------------------------------------------------------

  static void append(Level& level, const OrderPtr order) {
    order->previous_ = level.tail;
    order->next_ = nullptr;
    if (level.tail == nullptr) {
      level.head = order;
    } else {
      level.tail->next_ = order;
    }
    level.tail = order;
  }

  static void unlink(Level& level, const OrderPtr order) {
    if (order->previous_ == nullptr) {
      level.head = order->next_;
    } else {
      order->previous_->next_ = order->next_;
    }
    if (order->next_ == nullptr) {
      level.tail = order->previous_;
    } else {
      order->next_->previous_ = order->previous_;
    }
    order->previous_ = nullptr;
    order->next_ = nullptr;
  }

  // The name index ------------------------------------------------------------------------------

  void allocateNames(const std::size_t capacity) {
    nameParticipants_.assign(capacity, 0);
    nameClients_.assign(capacity, 0);
    nameOrders_.assign(capacity, nullptr);
    nameMask_ = capacity - 1;
  }

  std::size_t slotOf(const std::uint32_t participantId, const std::uint64_t clientOrderId) const {
    std::uint64_t hash =
        clientOrderId * 0x9E3779B97F4A7C15ULL ^ participantId * 0xC2B2AE3D27D4EB4FULL;
    hash ^= hash >> 32;
    return static_cast<std::size_t>(hash) & nameMask_;
  }

  void namePut(const OrderPtr order) {
    if ((names_ + 1) * 2 > nameMask_ + 1) {
      grow();
    }
    std::size_t at = slotOf(order->participantId(), order->clientOrderId());
    while (nameOrders_[at] != nullptr) {
      at = (at + 1) & nameMask_;
    }
    nameParticipants_[at] = order->participantId();
    nameClients_[at] = order->clientOrderId();
    nameOrders_[at] = order;
    names_++;
  }

  void nameRemove(const std::uint32_t participantId, const std::uint64_t clientOrderId) {
    std::size_t at = slotOf(participantId, clientOrderId);
    while (nameParticipants_[at] != participantId || nameClients_[at] != clientOrderId) {
      at = (at + 1) & nameMask_;
    }
    names_--;
    // Backward shift rather than a tombstone: the table's occupancy is its contents, so the load
    // never quietly climbs and the steady state never rehashes (NFR-4.3).
    std::size_t hole = at;
    std::size_t probe = (hole + 1) & nameMask_;
    while (nameOrders_[probe] != nullptr) {
      const std::size_t home = slotOf(nameParticipants_[probe], nameClients_[probe]);
      const bool reachable = ((probe - home) & nameMask_) >= ((probe - hole) & nameMask_);
      if (reachable) {
        nameParticipants_[hole] = nameParticipants_[probe];
        nameClients_[hole] = nameClients_[probe];
        nameOrders_[hole] = nameOrders_[probe];
        hole = probe;
      }
      probe = (probe + 1) & nameMask_;
    }
    nameOrders_[hole] = nullptr;
  }

  void grow() {
    const std::vector<std::uint32_t> participants = std::move(nameParticipants_);
    const std::vector<std::uint64_t> clients = std::move(nameClients_);
    const std::vector<OrderPtr> orders = std::move(nameOrders_);
    allocateNames((nameMask_ + 1) * 2);
    names_ = 0;
    for (std::size_t at = 0; at < orders.size(); at++) {
      if (orders[at] != nullptr) {
        names_++;
        std::size_t to = slotOf(participants[at], clients[at]);
        while (nameOrders_[to] != nullptr) {
          to = (to + 1) & nameMask_;
        }
        nameParticipants_[to] = participants[at];
        nameClients_[to] = clients[at];
        nameOrders_[to] = orders[at];
      }
    }
  }

  Level* bids_ = nullptr;
  Level* asks_ = nullptr;
  Level* freeLevels_ = nullptr;
  Level* found_ = nullptr;
  std::deque<Level> levels_;

  std::vector<std::uint32_t> nameParticipants_;
  std::vector<std::uint64_t> nameClients_;
  std::vector<OrderPtr> nameOrders_;
  std::size_t nameMask_ = 0;
  std::size_t names_ = 0;
};

}  // namespace io::github::giovanicaprison::matching::lean::pooled
