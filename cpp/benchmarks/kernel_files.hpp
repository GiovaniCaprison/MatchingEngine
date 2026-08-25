// Reads the kernel's own files under a root, mirroring the Java reader: one place decides what
// counts as unreadable, and a file the kernel does not expose and a platform that never had it
// both come back as nothing, which callers record as unavailable rather than wrong.

#pragma once

#include <filesystem>
#include <fstream>
#include <optional>
#include <sstream>
#include <string>

namespace io::github::giovanicaprison::matching::benchmarks::kernel_files {

inline std::optional<std::string> read(const std::filesystem::path& file) {
  std::error_code error;
  if (!std::filesystem::is_regular_file(file, error) &&
      !std::filesystem::is_character_file(file, error)) {
    // /proc and /sys entries are regular; a directory or an absent path is nothing to read.
    if (!std::filesystem::exists(file, error) || std::filesystem::is_directory(file, error)) {
      return std::nullopt;
    }
  }
  std::ifstream stream(file);
  if (!stream) {
    return std::nullopt;
  }
  std::ostringstream content;
  content << stream.rdbuf();
  return content.str();
}

inline std::string stripped(const std::string& raw) {
  const auto first = raw.find_first_not_of(" \t\r\n");
  if (first == std::string::npos) {
    return "";
  }
  const auto last = raw.find_last_not_of(" \t\r\n");
  return raw.substr(first, last - first + 1);
}

inline std::optional<std::string> firstLine(const std::filesystem::path& file) {
  const auto content = read(file);
  if (!content.has_value()) {
    return std::nullopt;
  }
  return stripped(content->substr(0, content->find('\n')));
}

inline std::optional<std::string> firstLine(const std::filesystem::path& root,
                                            const std::string& path) {
  return firstLine(root / path);
}

// The value after the colon on the line opening with the key, as /proc lays it out.
inline std::optional<std::string> keyed(const std::filesystem::path& root, const std::string& path,
                                        const std::string& key) {
  const auto content = read(root / path);
  if (!content.has_value()) {
    return std::nullopt;
  }
  std::istringstream lines(*content);
  std::string line;
  while (std::getline(lines, line)) {
    if (line.rfind(key, 0) == 0) {
      const auto colon = line.find(':');
      if (colon != std::string::npos) {
        return stripped(line.substr(colon + 1));
      }
    }
  }
  return std::nullopt;
}

}  // namespace io::github::giovanicaprison::matching::benchmarks::kernel_files
