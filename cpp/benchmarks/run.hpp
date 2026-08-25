// One run's directory and the name it is known by, mirroring the Java naming so a campaign's
// directory holds both languages' runs side by side and one analysis reads them all.

#pragma once

#include <chrono>
#include <ctime>
#include <filesystem>
#include <string>

namespace io::github::giovanicaprison::matching::benchmarks {

struct Run {
  std::string id;
  std::filesystem::path directory;
  std::string startedAt;

  static Run create(const std::filesystem::path& results, const std::string& label) {
    const std::time_t now = std::time(nullptr);
    std::tm utc{};
    gmtime_r(&now, &utc);
    char stamp[32];
    std::strftime(stamp, sizeof(stamp), "%Y%m%dT%H%M%SZ", &utc);
    char iso[32];
    std::strftime(iso, sizeof(iso), "%Y-%m-%dT%H:%M:%SZ", &utc);
    Run run;
    run.id = std::string(stamp) + "-" + label;
    run.directory = results / run.id;
    run.startedAt = iso;
    std::filesystem::create_directories(run.directory);
    return run;
  }

  std::filesystem::path file(const std::string& name) const { return directory / name; }
};

}  // namespace io::github::giovanicaprison::matching::benchmarks
