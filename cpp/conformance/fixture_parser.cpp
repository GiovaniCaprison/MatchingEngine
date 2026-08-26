#include "conformance/fixture_parser.hpp"

#include <fstream>
#include <sstream>

namespace io::github::giovanicaprison::matching::conformance {

namespace {

// The least number of words each command needs, before any optional ones.
int minimumArity(const Directive directive) {
  switch (directive) {
    case Directive::INSTRUMENT:
    case Directive::SESSION:
      return 1;
    case Directive::NEW:
      return 5;
    case Directive::CANCEL:
      return 1;
    case Directive::REPLACE:
      return 3;
    case Directive::MASSCANCEL:
      return 1;
  }
  return 0;
}

const char* directiveName(const Directive directive) {
  switch (directive) {
    case Directive::INSTRUMENT:
      return "INSTRUMENT";
    case Directive::SESSION:
      return "SESSION";
    case Directive::NEW:
      return "NEW";
    case Directive::CANCEL:
      return "CANCEL";
    case Directive::REPLACE:
      return "REPLACE";
    case Directive::MASSCANCEL:
      return "MASSCANCEL";
  }
  return "?";
}

std::string stripped(const std::string& raw) {
  const auto first = raw.find_first_not_of(" \t\r");
  if (first == std::string::npos) {
    return "";
  }
  const auto last = raw.find_last_not_of(" \t\r");
  return raw.substr(first, last - first + 1);
}

std::vector<std::string> words(const std::string& line) {
  std::vector<std::string> found;
  std::istringstream stream(line);
  std::string word;
  while (stream >> word) {
    found.push_back(word);
  }
  return found;
}

Element element(const std::string& name, const int number, const std::string& line) {
  const std::vector<std::string> parts = words(line);
  const std::string& first = parts.front();
  if (const auto directive = directiveOf(first)) {
    const std::vector<std::string> arguments(parts.begin() + 1, parts.end());
    const int arity = minimumArity(*directive);
    if (std::cmp_less(arguments.size(), arity)) {
      throw MalformedFixture(name, number,
                             std::string(directiveName(*directive)) + " needs at least " +
                                 std::to_string(arity) + " words");
    }
    return Command{line, number, *directive, arguments};
  }
  if (verbOf(first)) {
    return Expected{line, number};
  }
  throw MalformedFixture(name, number, first + " is neither a directive nor an output verb");
}

void requireInstrumentFirst(const Fixture& fixture) {
  const std::vector<Command> commands = fixture.commands();
  if (commands.empty() || commands.front().directive != Directive::INSTRUMENT) {
    throw MalformedFixture(fixture.name, 1, "the first command must be INSTRUMENT");
  }
  for (std::size_t at = 1; at < commands.size(); at++) {
    if (commands[at].directive == Directive::INSTRUMENT) {
      throw MalformedFixture(fixture.name, 1,
                             "an instrument is defined once for the life of an engine");
    }
  }
}

}  // namespace

Fixture parseFixture(const std::string& name, const std::string& content) {
  Fixture fixture;
  fixture.name = name;
  int number = 0;
  std::istringstream lines(content);
  std::string raw;
  while (std::getline(lines, raw)) {
    number++;
    const std::string line = stripped(raw);
    if (!line.empty() && line.front() == '#') {
      if (fixture.title.empty()) {
        fixture.title = stripped(line.substr(1));
      }
      continue;
    }
    if (line.empty()) {
      continue;
    }
    fixture.elements.push_back(element(name, number, line));
  }
  requireInstrumentFirst(fixture);
  return fixture;
}

Fixture parseFixture(const std::filesystem::path& file) {
  std::ifstream stream(file);
  if (!stream) {
    throw std::runtime_error("cannot read fixture " + file.string());
  }
  std::ostringstream content;
  content << stream.rdbuf();
  return parseFixture(file.filename().string(), content.str());
}

}  // namespace io::github::giovanicaprison::matching::conformance
