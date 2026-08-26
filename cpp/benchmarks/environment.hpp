// The machine and the runtime, as they actually are at the moment of a run, mirroring the Java
// probes name for name so one analysis reads both languages' manifests. Read from the kernel's own
// files rather than from a setup script's exit code, with the root a parameter so the probes are
// tested against a directory standing in for a machine.

#pragma once

#include <cstdint>
#include <filesystem>
#include <optional>
#include <sstream>
#include <string>
#include <vector>

#include "benchmarks/build_info.hpp"
#include "benchmarks/kernel_files.hpp"
#include "benchmarks/setting.hpp"

namespace io::github::giovanicaprison::matching::benchmarks {

class Environment {
 public:
  static Environment ofThisMachine() { return Environment("/"); }

  static Environment reading(const std::filesystem::path& root) { return Environment(root); }

  const std::vector<Setting>& settings() const { return settings_; }

  // Nothing a measurement needs is missing or contradicted.
  bool measurementGrade() const {
    for (const Setting& setting : settings_) {
      if (!setting.satisfied()) {
        return false;
      }
    }
    return true;
  }

  std::size_t failures() const {
    std::size_t count = 0;
    for (const Setting& setting : settings_) {
      if (!setting.satisfied()) {
        count++;
      }
    }
    return count;
  }

  // Whether one core is the kernel's to schedule on, sibling included, judged once a core exists.
  std::vector<Setting> isolationOf(const int core) const {
    return {isolates("core isolated", "isolcpus", core),
            isolates("core tickless", "nohz_full", core),
            isolates("core callback offloaded", "rcu_nocbs", core), siblingOffline(core)};
  }

 private:
  explicit Environment(std::filesystem::path root) : root_(std::move(root)) { probe(); }

  void probe() {
    settings_.push_back(Setting::recorded("kernel", "proc/version",
                                          kernel_files::firstLine(root_, "proc/version")));
    settings_.push_back(Setting::recorded(
        "processor", "proc/cpuinfo", kernel_files::keyed(root_, "proc/cpuinfo", "model name")));
    settings_.push_back(Setting::recorded("cores", "proc/cpuinfo", processorCount()));
    settings_.push_back(Setting::recorded(
        "instance", "dmi/board_asset_tag",
        kernel_files::firstLine(root_, "sys/devices/virtual/dmi/id/board_asset_tag")));
    settings_.push_back(Setting::recorded("command line", "proc/cmdline",
                                          kernel_files::firstLine(root_, "proc/cmdline")));

    settings_.push_back(Setting::required(
        "scaling governor", "cpufreq/scaling_governor", "performance",
        kernel_files::firstLine(root_, "sys/devices/system/cpu/cpu0/cpufreq/scaling_governor")));
    settings_.push_back(Setting::required(
        "turbo disabled", "intel_pstate/no_turbo", "1",
        kernel_files::firstLine(root_, "sys/devices/system/cpu/intel_pstate/no_turbo")));
    settings_.push_back(Setting::required("processor max c-state", "proc/cmdline", "1",
                                          parameter("processor.max_cstate")));
    settings_.push_back(Setting::required("intel idle max c-state", "proc/cmdline", "0",
                                          parameter("intel_idle.max_cstate")));

    settings_.push_back(Setting::recorded("isolated cores", "proc/cmdline", parameter("isolcpus")));
    settings_.push_back(
        Setting::recorded("tickless cores", "proc/cmdline", parameter("nohz_full")));
    settings_.push_back(
        Setting::recorded("callback offloaded cores", "proc/cmdline", parameter("rcu_nocbs")));

    settings_.push_back(Setting::required(
        "clocksource", "clocksource0/current_clocksource", "tsc",
        kernel_files::firstLine(
            root_, "sys/devices/system/clocksource/clocksource0/current_clocksource")));
    settings_.push_back(Setting::required("perf event paranoia", "kernel/perf_event_paranoid", "1",
                                          atMostOne("proc/sys/kernel/perf_event_paranoid")));
    settings_.push_back(
        Setting::required("kernel pointers readable", "kernel/kptr_restrict", "0",
                          kernel_files::firstLine(root_, "proc/sys/kernel/kptr_restrict")));

    settings_.push_back(Setting::recorded(
        "transparent huge pages", "transparent_hugepage/enabled",
        kernel_files::firstLine(root_, "sys/kernel/mm/transparent_hugepage/enabled")));
    settings_.push_back(
        Setting::recorded("huge pages reserved", "proc/meminfo",
                          kernel_files::keyed(root_, "proc/meminfo", "HugePages_Total")));
    settings_.push_back(Setting::required("swap off", "proc/meminfo", "0 kB",
                                          kernel_files::keyed(root_, "proc/meminfo", "SwapTotal")));

    // The runtime here is the compiler and its flags, which decide as much as the source does.
    settings_.push_back(Setting::recorded("runtime", "build", build_info::compiler()));
    settings_.push_back(Setting::recorded("runtime arguments", "build", build_info::flags()));
  }

  std::optional<std::string> processorCount() const {
    const auto content = kernel_files::read(root_ / "proc/cpuinfo");
    if (!content.has_value()) {
      return std::nullopt;
    }
    std::istringstream lines(*content);
    std::string line;
    long count = 0;
    while (std::getline(lines, line)) {
      if (line.starts_with("processor\t")) {
        count++;
      }
    }
    return std::to_string(count);
  }

  std::optional<std::string> parameter(const std::string& name) const {
    const auto cmdline = kernel_files::firstLine(root_, "proc/cmdline");
    if (!cmdline.has_value()) {
      return std::nullopt;
    }
    std::istringstream words(*cmdline);
    std::string word;
    while (words >> word) {
      if (word.starts_with(name + "=")) {
        return word.substr(name.size() + 1);
      }
    }
    return std::nullopt;
  }

  // Paranoia at or below one is what the instruments need, so a lower value is not a failure.
  std::optional<std::string> atMostOne(const std::string& path) const {
    auto value = kernel_files::firstLine(root_, path);
    if (!value.has_value()) {
      return std::nullopt;
    }
    try {
      return std::stoi(*value) <= 1 ? std::optional<std::string>("1") : value;
    } catch (const std::exception&) {
      return value;
    }
  }

  Setting isolates(const std::string& name, const std::string& list, const int core) const {
    const auto cores = parameter(list);
    if (!cores.has_value()) {
      return Setting::required(name, "proc/cmdline", "true", std::nullopt);
    }
    return Setting::required(name, "proc/cmdline", "true",
                             contains(*cores, core) ? "true" : "false");
  }

  Setting siblingOffline(const int core) const {
    const std::string source = "topology/thread_siblings_list";
    const auto siblings = kernel_files::firstLine(
        root_, "sys/devices/system/cpu/cpu" + std::to_string(core) + "/" + source);
    if (!siblings.has_value()) {
      return Setting::required("sibling offline", source, "true", std::nullopt);
    }
    const bool alone = contains(*siblings, core) && siblings->find(',') == std::string::npos &&
                       siblings->find('-') == std::string::npos;
    return Setting::required("sibling offline", source, "true", alone ? "true" : "false");
  }

  // A kernel cpu list like 2-5,8, asked whether it contains one core.
  static bool contains(const std::string& list, const int core) {
    std::istringstream parts(list);
    std::string part;
    while (std::getline(parts, part, ',')) {
      const auto dash = part.find('-');
      try {
        if (dash == std::string::npos) {
          if (std::stoi(part) == core) {
            return true;
          }
        } else if (std::stoi(part.substr(0, dash)) <= core &&
                   core <= std::stoi(part.substr(dash + 1))) {
          return true;
        }
      } catch (const std::exception&) {
        // A list this cannot read is recorded verbatim elsewhere; here it just does not match.
      }
    }
    return false;
  }

  std::filesystem::path root_;
  std::vector<Setting> settings_;
};

}  // namespace io::github::giovanicaprison::matching::benchmarks
