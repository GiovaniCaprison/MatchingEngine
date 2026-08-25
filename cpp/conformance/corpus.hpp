// The fixtures on disk. They sit above both language trees, so neither owns the file that holds
// both to the same behaviour, and the directory is found by walking up rather than configured, so
// this works from the build tree and from anywhere inside the repository.

#pragma once

#include <filesystem>
#include <vector>

#include "conformance/fixture.hpp"

namespace io::github::giovanicaprison::matching::conformance {

std::filesystem::path corpusDirectory();
std::vector<Fixture> corpusFixtures();

}  // namespace io::github::giovanicaprison::matching::conformance
