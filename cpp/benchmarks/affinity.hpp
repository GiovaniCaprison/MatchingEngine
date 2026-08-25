// Puts a thread on one core and checks that it stayed there, mirroring the Java pinner: the call
// pins whichever thread makes it, the mask is read back rather than assumed because a call that
// returned success and a thread still free to move would be the worst outcome, and everywhere the
// platform lacks the call every pin reads as unavailable, which is the honest outcome on a laptop.

#pragma once

#include <string>

#include "benchmarks/setting.hpp"

#if defined(__linux__)
#include <sched.h>
#endif

namespace io::github::giovanicaprison::matching::benchmarks::affinity {

inline Setting pin(const std::string& name, const int core) {
  const std::string setting = name + " core";
#if defined(__linux__)
  cpu_set_t mask;
  CPU_ZERO(&mask);
  CPU_SET(core, &mask);
  if (sched_setaffinity(0, sizeof(mask), &mask) != 0) {
    return Setting::required(setting, "sched_setaffinity", std::to_string(core), "refused");
  }
  CPU_ZERO(&mask);
  if (sched_getaffinity(0, sizeof(mask), &mask) != 0) {
    return Setting::required(setting, "sched_getaffinity", std::to_string(core), "unreadable");
  }
  int found = -1;
  int count = 0;
  for (int cpu = 0; cpu < CPU_SETSIZE; cpu++) {
    if (CPU_ISSET(cpu, &mask)) {
      count++;
      found = cpu;
    }
  }
  const std::string actual = count == 1 ? std::to_string(found) : std::to_string(count) + " cores";
  return Setting::required(setting, "sched_getaffinity", std::to_string(core), actual);
#else
  return Setting::required(setting, "sched_setaffinity", std::to_string(core), std::nullopt);
#endif
}

}  // namespace io::github::giovanicaprison::matching::benchmarks::affinity
