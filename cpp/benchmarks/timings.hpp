// Four timestamps per command, mirroring the Java arrays and the file they leave behind: intended
// to published is the driver being late, published to started is the ring, started to finished is
// the engine, intended to finished is what a client would have seen. The raw file is METIMES1,
// byte compatible with the Java runner's, so one analysis reads both.

#pragma once

#include <algorithm>
#include <cstdint>
#include <fstream>
#include <string>
#include <vector>

namespace io::github::giovanicaprison::matching::benchmarks {

class Timings {
 public:
  Timings(const std::size_t capacity, const std::size_t reportFrom)
      : intended_(capacity),
        published_(capacity),
        started_(capacity),
        finished_(capacity),
        reportFrom_(reportFrom) {}

  void intended(const std::size_t command, const std::int64_t at) { intended_[command] = at; }
  void published(const std::size_t command, const std::int64_t at) { published_[command] = at; }

  void record(const std::size_t command, const std::int64_t from, const std::int64_t to) {
    started_[command] = from;
    finished_[command] = to;
    recorded_++;
  }

  std::size_t recorded() const { return recorded_; }

  struct Summary {
    std::int64_t count = 0;
    std::int64_t p50 = 0;
    std::int64_t p99 = 0;
    std::int64_t p999 = 0;
    std::int64_t max = 0;
  };

  Summary service() const {
    return summary([this](const std::size_t at) { return finished_[at] - started_[at]; });
  }

  Summary response() const {
    return summary([this](const std::size_t at) { return finished_[at] - intended_[at]; });
  }

  Summary offered() const {
    return summary([this](const std::size_t at) { return published_[at] - intended_[at]; });
  }

  void writeTimings(const std::string& file) const {
    std::ofstream out(file, std::ios::binary);
    out.write("METIMES1", 8);
    const std::int32_t count = static_cast<std::int32_t>(recorded_);
    out.write(reinterpret_cast<const char*>(&count), sizeof(count));
    for (std::size_t command = 0; command < recorded_; command++) {
      const std::int64_t row[4] = {intended_[command], published_[command], started_[command],
                                   finished_[command]};
      out.write(reinterpret_cast<const char*>(row), sizeof(row));
    }
  }

 private:
  template <typename Duration>
  Summary summary(Duration&& duration) const {
    std::vector<std::int64_t> values;
    values.reserve(recorded_ > reportFrom_ ? recorded_ - reportFrom_ : 0);
    for (std::size_t command = reportFrom_; command < recorded_; command++) {
      values.push_back(duration(command));
    }
    std::sort(values.begin(), values.end());
    Summary out;
    out.count = static_cast<std::int64_t>(values.size());
    if (values.empty()) {
      return out;
    }
    const auto at = [&values](const double percentile) {
      const std::size_t index =
          std::min(values.size() - 1, static_cast<std::size_t>(percentile / 100.0 * values.size()));
      return values[index];
    };
    out.p50 = at(50.0);
    out.p99 = at(99.0);
    out.p999 = at(99.9);
    out.max = values.back();
    return out;
  }

  std::vector<std::int64_t> intended_;
  std::vector<std::int64_t> published_;
  std::vector<std::int64_t> started_;
  std::vector<std::int64_t> finished_;
  const std::size_t reportFrom_;
  std::size_t recorded_ = 0;
};

}  // namespace io::github::giovanicaprison::matching::benchmarks
