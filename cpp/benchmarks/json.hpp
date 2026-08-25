// Writes the JSON a run's artifacts are read from, mirroring the Java writer so the two languages
// produce artifacts one analysis script reads without caring which side made them. Writing it is a
// hundred lines, and a serialisation library in the measured process is a dependency earning
// nothing: none of this runs while anything is being timed.

#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace io::github::giovanicaprison::matching::benchmarks {

class Json {
 public:
  Json& object() {
    separate();
    open('{', '}');
    return *this;
  }

  Json& object(const std::string& name) {
    key(name);
    open('{', '}');
    return *this;
  }

  Json& array(const std::string& name) {
    key(name);
    open('[', ']');
    return *this;
  }

  Json& end() {
    const Scope scope = scopes_.back();
    scopes_.pop_back();
    if (!scope.empty) {
      newLine();
    }
    text_ += scope.closing;
    return *this;
  }

  Json& field(const std::string& name, const std::string& value) {
    key(name);
    text_ += quoted(value);
    return *this;
  }

  Json& fieldOrNull(const std::string& name, const std::string& value, const bool present) {
    key(name);
    text_ += present ? quoted(value) : "null";
    return *this;
  }

  Json& field(const std::string& name, const std::int64_t value) {
    key(name);
    text_ += std::to_string(value);
    return *this;
  }

  Json& field(const std::string& name, const std::uint64_t value) {
    key(name);
    text_ += std::to_string(value);
    return *this;
  }

  Json& field(const std::string& name, const bool value) {
    key(name);
    text_ += value ? "true" : "false";
    return *this;
  }

  std::string done() { return text_ + "\n"; }

 private:
  struct Scope {
    char closing;
    bool empty;
  };

  void open(const char opening, const char closing) {
    text_ += opening;
    scopes_.push_back(Scope{closing, true});
  }

  void key(const std::string& name) {
    separate();
    text_ += quoted(name) + ": ";
  }

  void separate() {
    if (scopes_.empty()) {
      return;
    }
    if (scopes_.back().empty) {
      scopes_.back().empty = false;
    } else {
      text_ += ',';
    }
    newLine();
  }

  void newLine() {
    text_ += '\n';
    text_.append(2 * scopes_.size(), ' ');
  }

  static std::string quoted(const std::string& value) {
    std::string out = "\"";
    for (const char character : value) {
      switch (character) {
        case '"':
          out += "\\\"";
          break;
        case '\\':
          out += "\\\\";
          break;
        case '\n':
          out += "\\n";
          break;
        case '\r':
          out += "\\r";
          break;
        case '\t':
          out += "\\t";
          break;
        default:
          out += character;
      }
    }
    return out + "\"";
  }

  std::string text_;
  std::vector<Scope> scopes_;
};

}  // namespace io::github::giovanicaprison::matching::benchmarks
