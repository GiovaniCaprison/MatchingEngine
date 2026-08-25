// The allocation proof in this language (NFR-4.3): the allocator refuses after initialisation, as
// the methodology asks, and refusal here is counting rather than crashing so the probe can say how
// many times it was asked. The log is read and the engine built while the allocator still says
// yes; every command after that runs against an allocator that records each request, and one
// request is a failure. Events go into a fixed buffer and are dropped, because the proof is about
// the engine's memory and capturing output would put the harness's allocation on the engine's
// bill.

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <new>

#include "api/event_publisher.hpp"
#include "conformance/command_log.hpp"
#include "lean-pooled/lean_engine.hpp"

namespace {

bool locked = false;
std::uint64_t denied = 0;

class DiscardingPublisher final
    : public io::github::giovanicaprison::matching::api::EventPublisher {
 public:
  std::size_t claim(std::size_t) override { return 0; }
  char* buffer() override { return space_; }
  void commit() override {}

 private:
  char space_[1024] = {};
};

}  // namespace

void* operator new(const std::size_t size) {
  if (locked) {
    denied++;
  }
  void* const memory = std::malloc(size);
  if (memory == nullptr) {
    throw std::bad_alloc();
  }
  return memory;
}

void operator delete(void* memory) noexcept { std::free(memory); }

void operator delete(void* memory, std::size_t) noexcept { std::free(memory); }

int main(const int count, char** arguments) {
  namespace matching = io::github::giovanicaprison::matching;
  if (count != 2) {
    std::fprintf(stderr, "usage: <command log>\n");
    return 2;
  }
  matching::conformance::CommandLog log;
  try {
    log = matching::conformance::CommandLog::readFrom(arguments[1]);
  } catch (const std::exception& refused) {
    std::fprintf(stderr, "%s\n", refused.what());
    return 2;
  }
  DiscardingPublisher events;
  matching::lean::pooled::LeanEngine engine(events);

  locked = true;
  for (std::size_t command = 0; command < log.count(); command++) {
    engine.onCommand(log.buffer.data(), log.offsets[command], log.lengths[command]);
  }
  locked = false;

  if (denied != 0) {
    std::fprintf(stderr, "the steady state asked the allocator %llu times\n",
                 static_cast<unsigned long long>(denied));
    return 1;
  }
  return 0;
}
