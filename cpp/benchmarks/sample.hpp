// The machine's transient state at one moment of a run, around the measured core, mirroring the
// Java sampler name for name: one before, one after, and the difference between the pair is the
// claim. Everything is recorded rather than required, and a counter the kernel does not expose
// reads as unavailable, never as zero, because a zero that means could-not-look would subtract
// cleanly from its pair and lie.

#pragma once

#include <algorithm>
#include <filesystem>
#include <optional>
#include <sstream>
#include <string>
#include <vector>

#include "benchmarks/kernel_files.hpp"
#include "benchmarks/setting.hpp"

namespace io::github::giovanicaprison::matching::benchmarks::sample {

namespace detail {

// One field of the line describing the core, in a file of space separated counters.
inline std::optional<std::string> token(const std::filesystem::path& root, const std::string& path,
                                        const int core, const std::size_t field) {
  const auto content = kernel_files::read(root / path);
  if (!content.has_value()) {
    return std::nullopt;
  }
  const std::string wanted = "cpu" + std::to_string(core) + " ";
  std::istringstream lines(*content);
  std::string line;
  while (std::getline(lines, line)) {
    if (line.starts_with(wanted)) {
      std::istringstream words(line);
      std::string word;
      for (std::size_t at = 0; words >> word; at++) {
        if (at == field) {
          return word;
        }
      }
      return std::nullopt;
    }
  }
  return std::nullopt;
}

inline std::optional<std::string> packageTemperature(const std::filesystem::path& root) {
  const std::filesystem::path thermal = root / "sys/class/thermal";
  std::error_code error;
  if (!std::filesystem::is_directory(thermal, error)) {
    return std::nullopt;
  }
  std::vector<std::filesystem::path> zones;
  for (const auto& entry : std::filesystem::directory_iterator(thermal, error)) {
    zones.push_back(entry.path());
  }
  std::sort(zones.begin(), zones.end());
  for (const std::filesystem::path& zone : zones) {
    if (kernel_files::firstLine(zone / "type") == std::optional<std::string>("x86_pkg_temp")) {
      return kernel_files::firstLine(zone / "temp");
    }
  }
  return std::nullopt;
}

inline std::vector<Setting> core(const std::optional<std::string>& frequency,
                                 const std::optional<std::string>& temperature,
                                 const std::optional<std::string>& switches,
                                 const std::optional<std::string>& steal) {
  return {Setting::recorded("core frequency kHz", "cpufreq/scaling_cur_freq", frequency),
          Setting::recorded("package temperature", "thermal_zone*/temp", temperature),
          Setting::recorded("core context switches", "proc/schedstat", switches),
          Setting::recorded("core steal ticks", "proc/stat", steal)};
}

}  // namespace detail

// What the kernel says about one core; a run that asked for no core has nothing to sample.
inline std::vector<Setting> ofCore(const std::filesystem::path& root, const int core) {
  if (core < 0) {
    return detail::core(std::nullopt, std::nullopt, std::nullopt, std::nullopt);
  }
  return detail::core(
      kernel_files::firstLine(
          root, "sys/devices/system/cpu/cpu" + std::to_string(core) + "/cpufreq/scaling_cur_freq"),
      detail::packageTemperature(root), detail::token(root, "proc/schedstat", core, 3),
      detail::token(root, "proc/stat", core, 8));
}

// The calling thread's own scheduling counts, which only it can read at the right moment.
inline std::vector<Setting> ofThisThread(const std::filesystem::path& root) {
  const std::string status = "proc/thread-self/status";
  return {Setting::recorded("thread voluntary switches", status,
                            kernel_files::keyed(root, status, "voluntary_ctxt_switches")),
          Setting::recorded("thread involuntary switches", status,
                            kernel_files::keyed(root, status, "nonvoluntary_ctxt_switches"))};
}

}  // namespace io::github::giovanicaprison::matching::benchmarks::sample
