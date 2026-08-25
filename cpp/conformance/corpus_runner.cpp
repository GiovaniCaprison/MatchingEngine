#include "conformance/corpus_runner.hpp"

#include <algorithm>
#include <sstream>

#include "conformance/command_writer.hpp"

namespace io::github::giovanicaprison::matching::conformance {

namespace {

// Comparison is over words rather than characters, so a fixture can align its columns.
std::string normalised(const std::string& line) {
  std::istringstream stream(line);
  std::string word;
  std::string joined;
  while (stream >> word) {
    if (!joined.empty()) {
      joined += ' ';
    }
    joined += word;
  }
  return joined;
}

std::string lineAt(const std::vector<std::string>& lines, const std::size_t at) {
  return at < lines.size() ? normalised(lines[at]) : "(nothing)";
}

}  // namespace

CorpusRunner::CorpusRunner() : events_(CAPACITY), reader_(references_, rebuilt_) {}

CorpusRunner::Result CorpusRunner::run(const Fixture& fixture, const EngineFactory& factory) {
  CorpusRunner runner;
  CommandWriter writer(runner.references_);
  const std::unique_ptr<api::MatchingEngine> engine = factory(runner);
  for (const Command& command : fixture.commands()) {
    runner.byCommand_.emplace_back(command, std::vector<std::string>());
    const std::size_t length = writer.write(command);
    engine->onCommand(writer.buffer(), 0, length);
  }
  return Result(fixture.name, fixture.expectedOutput(), runner.emitted_,
                std::move(runner.byCommand_), runner.rebuilt_.problems());
}

std::size_t CorpusRunner::claim(const std::size_t length) {
  if (cursor_ + length > CAPACITY) {
    cursor_ = 0;
  }
  claimed_ = cursor_;
  claimedLength_ = length;
  cursor_ += length;
  return claimed_;
}

void CorpusRunner::commit() {
  const std::string line = reader_.read(events_.data(), claimed_, claimedLength_);
  emitted_.push_back(line);
  byCommand_.back().second.push_back(line);
}

int CorpusRunner::Result::firstDifference() const {
  const std::size_t most = std::max(expected_.size(), emitted_.size());
  for (std::size_t at = 0; at < most; at++) {
    if (lineAt(expected_, at) != lineAt(emitted_, at)) {
      return static_cast<int>(at);
    }
  }
  return -1;
}

// The failure, and the fixture as it would read if the engine were right. Reading that diff is the
// point: a blessed snapshot is worth what the last person to look at it was paying attention to.
std::string CorpusRunner::Result::describe() const {
  if (passed()) {
    return name_ + " passed";
  }
  std::string said = name_;
  const int at = firstDifference();
  if (at >= 0) {
    said += " differs at output line " + std::to_string(at + 1) +
            "\n  expected: " + lineAt(expected_, static_cast<std::size_t>(at)) +
            "\n  actual:   " + lineAt(emitted_, static_cast<std::size_t>(at));
  }
  if (!problems_.empty()) {
    said += "\n  emitted a stream a consumer cannot follow:";
    for (const std::string& problem : problems_) {
      said += "\n    " + problem;
    }
  }
  return said + "\n\nthe run as a fixture:\n\n" + asFixture();
}

// The commands as written, each followed by the events it actually produced.
std::string CorpusRunner::Result::asFixture() const {
  std::string text;
  for (const auto& [command, events] : byCommand_) {
    text += command.text + '\n';
    for (const std::string& event : events) {
      text += event + '\n';
    }
    text += '\n';
  }
  return text;
}

}  // namespace io::github::giovanicaprison::matching::conformance
