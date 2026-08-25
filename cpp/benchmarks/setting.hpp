// One thing about the machine or the runtime, as found, mirroring the Java record: a setting that
// was asked for and did not take is worse than one nobody asked for, so a probe records what it
// wanted alongside what it got, and a run says which of the two it is.

#pragma once

#include <optional>
#include <string>

#include "benchmarks/json.hpp"

namespace io::github::giovanicaprison::matching::benchmarks {

struct Setting {
  enum class Status { OK, WRONG, UNAVAILABLE };

  std::string name;
  std::string source;
  std::optional<std::string> expected;
  std::optional<std::string> actual;
  Status status = Status::UNAVAILABLE;

  static Setting recorded(std::string name, std::string source, std::optional<std::string> actual) {
    Setting setting;
    setting.name = std::move(name);
    setting.source = std::move(source);
    setting.status = actual.has_value() ? Status::OK : Status::UNAVAILABLE;
    setting.actual = std::move(actual);
    return setting;
  }

  static Setting required(std::string name, std::string source, std::string expected,
                          std::optional<std::string> actual) {
    Setting setting;
    setting.name = std::move(name);
    setting.source = std::move(source);
    if (!actual.has_value()) {
      setting.status = Status::UNAVAILABLE;
    } else {
      setting.status = *actual == expected ? Status::OK : Status::WRONG;
    }
    setting.expected = std::move(expected);
    setting.actual = std::move(actual);
    return setting;
  }

  // A run is only measurement grade when every required setting is what it asked for.
  bool satisfied() const { return !expected.has_value() || status == Status::OK; }

  const char* statusName() const {
    switch (status) {
      case Status::OK:
        return "OK";
      case Status::WRONG:
        return "WRONG";
      default:
        return "UNAVAILABLE";
    }
  }

  // Written one way wherever a setting lands in an artifact, expected null where none was.
  void writeTo(Json& json) const {
    json.object()
        .field("name", name)
        .field("source", source)
        .fieldOrNull("expected", expected.value_or(""), expected.has_value())
        .fieldOrNull("actual", actual.value_or(""), actual.has_value())
        .field("status", std::string(statusName()))
        .end();
  }
};

}  // namespace io::github::giovanicaprison::matching::benchmarks
