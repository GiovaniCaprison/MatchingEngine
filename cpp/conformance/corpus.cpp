#include "conformance/corpus.hpp"

#include <algorithm>

#include "conformance/fixture_parser.hpp"

namespace io::github::giovanicaprison::matching::conformance {

std::filesystem::path corpusDirectory() {
  std::filesystem::path candidate = std::filesystem::current_path();
  while (true) {
    const std::filesystem::path corpus = candidate / "corpus";
    if (std::filesystem::is_directory(corpus)) {
      return corpus;
    }
    if (candidate == candidate.root_path()) {
      throw std::runtime_error("no corpus directory above " +
                               std::filesystem::current_path().string());
    }
    candidate = candidate.parent_path();
  }
}

std::vector<Fixture> corpusFixtures() {
  std::vector<std::filesystem::path> files;
  for (const auto& entry : std::filesystem::recursive_directory_iterator(corpusDirectory())) {
    if (entry.is_regular_file() && entry.path().extension() == ".txt") {
      files.push_back(entry.path());
    }
  }
  std::sort(files.begin(), files.end());
  std::vector<Fixture> fixtures;
  fixtures.reserve(files.size());
  for (const std::filesystem::path& file : files) {
    fixtures.push_back(parseFixture(file));
  }
  return fixtures;
}

}  // namespace io::github::giovanicaprison::matching::conformance
