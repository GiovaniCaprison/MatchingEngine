// What this binary was built with, recorded because a compiler and its flags decide as much as the
// source does, and METHODOLOGY requires optimisation level, link time optimisation, profile
// guidance and compiler identity per run. The values arrive from CMake as definitions, so the
// binary carries the truth about itself.

#pragma once

#include <string>

#ifndef MATCHING_BUILD_FLAGS
#define MATCHING_BUILD_FLAGS "unrecorded"
#endif

namespace io::github::giovanicaprison::matching::benchmarks::build_info {

inline std::string compiler() {
#if defined(__clang__)
  return std::string("clang ") + __clang_version__;
#elif defined(__GNUC__)
  return std::string("gcc ") + std::to_string(__GNUC__) + "." + std::to_string(__GNUC_MINOR__) +
         "." + std::to_string(__GNUC_PATCHLEVEL__);
#else
  return "unknown";
#endif
}

inline std::string flags() { return MATCHING_BUILD_FLAGS; }

}  // namespace io::github::giovanicaprison::matching::benchmarks::build_info
