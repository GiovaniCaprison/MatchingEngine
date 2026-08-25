// One parsed fixture: the commands to send and the output they must produce, in the order the file
// holds them. The C++ reading of the format TESTING.md specifies, kept deliberately close to the
// Java one so the two can be read side by side: the fixtures are the contract, and this parser is
// the second independent reader that holds the format to being unambiguous.

#pragma once

#include <optional>
#include <stdexcept>
#include <string>
#include <string_view>
#include <variant>
#include <vector>

namespace io::github::giovanicaprison::matching::conformance {

// The commands a fixture can send and the events it can expect, disjoint on purpose: that is what
// lets a fixture put an event on the line below the command that caused it without a marker.
enum class Directive { INSTRUMENT, SESSION, NEW, CANCEL, REPLACE, MASSCANCEL };
enum class Verb {
  ACCEPTED,
  REJECTED,
  RESTED,
  EXECUTED,
  REDUCED,
  REMOVED,
  TRIGGERED,
  STATE,
  INDICATIVE
};

inline std::optional<Directive> directiveOf(std::string_view word) {
  if (word == "INSTRUMENT") return Directive::INSTRUMENT;
  if (word == "SESSION") return Directive::SESSION;
  if (word == "NEW") return Directive::NEW;
  if (word == "CANCEL") return Directive::CANCEL;
  if (word == "REPLACE") return Directive::REPLACE;
  if (word == "MASSCANCEL") return Directive::MASSCANCEL;
  return std::nullopt;
}

inline std::optional<Verb> verbOf(std::string_view word) {
  if (word == "ACCEPTED") return Verb::ACCEPTED;
  if (word == "REJECTED") return Verb::REJECTED;
  if (word == "RESTED") return Verb::RESTED;
  if (word == "EXECUTED") return Verb::EXECUTED;
  if (word == "REDUCED") return Verb::REDUCED;
  if (word == "REMOVED") return Verb::REMOVED;
  if (word == "TRIGGERED") return Verb::TRIGGERED;
  if (word == "STATE") return Verb::STATE;
  if (word == "INDICATIVE") return Verb::INDICATIVE;
  return std::nullopt;
}

// A command line: the text as written, where it sits, which command it is, and the words after it.
struct Command {
  std::string text;
  int line = 0;
  Directive directive = Directive::INSTRUMENT;
  std::vector<std::string> arguments;
};

// An expected output line. The text as written is what the comparison uses.
struct Expected {
  std::string text;
  int line = 0;
};

using Element = std::variant<Command, Expected>;

// A fixture the runner refuses to guess at.
class MalformedFixture : public std::runtime_error {
 public:
  MalformedFixture(const std::string& name, int line, const std::string& problem)
      : std::runtime_error(name + ":" + std::to_string(line) + " " + problem) {}
};

struct Fixture {
  // The title is the first comment line, and for a rule it opens with the requirement id it
  // states, which is where the Java coverage gate reads the claim from.
  std::string name;
  std::string title;
  std::vector<Element> elements;

  std::vector<Command> commands() const {
    std::vector<Command> found;
    for (const Element& element : elements) {
      if (const Command* command = std::get_if<Command>(&element)) {
        found.push_back(*command);
      }
    }
    return found;
  }

  // The blessed output, in order. Nothing about which command produced which line.
  std::vector<std::string> expectedOutput() const {
    std::vector<std::string> found;
    for (const Element& element : elements) {
      if (const Expected* expected = std::get_if<Expected>(&element)) {
        found.push_back(expected->text);
      }
    }
    return found;
  }
};

}  // namespace io::github::giovanicaprison::matching::conformance
