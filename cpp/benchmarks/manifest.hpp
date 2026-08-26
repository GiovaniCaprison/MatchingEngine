// What a run was: which engine, on which machine, over which log. Written before the measurement
// so a run that dies still says what it was attempting, and graded the way the Java manifest
// grades: the environment and the measured core's isolation together decide whether the numbers
// can be believed, and an uncontrolled run is labelled rather than refused.
//
// The flow block carries its source and its count and nothing else, because this runner only
// replays logs: the composition that made a generated log is the generator's manifest to describe,
// and a real session's file describes itself.

#pragma once

#include <cstdint>
#include <fstream>
#include <string>
#include <vector>

#include "benchmarks/environment.hpp"
#include "benchmarks/json.hpp"
#include "benchmarks/run.hpp"
#include "benchmarks/setting.hpp"

namespace io::github::giovanicaprison::matching::benchmarks {

struct Manifest {
  const Run& run;
  std::string implementation;
  std::string commandLine;
  const Environment& environment;
  std::vector<Setting> isolation;
  std::string flowSource;
  std::uint64_t flowCommands = 0;

  std::string grade() const {
    if (!environment.measurementGrade()) {
      return "exploratory";
    }
    for (const Setting& setting : isolation) {
      if (!setting.satisfied()) {
        return "exploratory";
      }
    }
    return "measurement";
  }

  std::string toJson() const {
    Json json;
    json.object()
        .field("run", run.id)
        .field("startedAt", run.startedAt)
        .field("implementation", implementation)
        .field("commit", std::string("unknown"))
        .field("commandLine", commandLine)
        .field("grade", grade());
    json.object("flow")
        .field("source", flowSource)
        .field("seed", static_cast<std::int64_t>(0))
        .field("commands", flowCommands)
        .field("restingOrders", static_cast<std::int64_t>(0))
        .end();
    json.array("environment");
    for (const Setting& setting : environment.settings()) {
      setting.writeTo(json);
    }
    json.end();
    json.array("isolation");
    for (const Setting& setting : isolation) {
      setting.writeTo(json);
    }
    json.end();
    json.end();
    return json.done();
  }

  void write() const {
    std::ofstream out(run.file("manifest.json"));
    out << toJson();
  }
};

}  // namespace io::github::giovanicaprison::matching::benchmarks
