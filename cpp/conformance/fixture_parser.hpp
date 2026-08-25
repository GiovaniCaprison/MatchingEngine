// Reads the fixture format described in TESTING.md. A malformed line fails immediately and names
// the file and the line, because a fixture that parses loosely produces a comparison failure
// somewhere else entirely.

#pragma once

#include <filesystem>
#include <string>

#include "conformance/fixture.hpp"

namespace io::github::giovanicaprison::matching::conformance {

Fixture parseFixture(const std::string& name, const std::string& content);
Fixture parseFixture(const std::filesystem::path& file);

}  // namespace io::github::giovanicaprison::matching::conformance
