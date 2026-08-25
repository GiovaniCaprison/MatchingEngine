cmake_minimum_required(VERSION 3.25)
project(matching-engine CXX)

# One rung per target as they arrive, plus conformance and benchmarks. The schema and the corpus sit
# above this directory, so neither language owns the contract.

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

if(NOT CMAKE_BUILD_TYPE)
  # A measurement built without optimisation measures the debug build. Release is the only default
  # that is not a mistake here.
  set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
endif()

set(MATCHING_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/..)
set(MATCHING_SCHEMA ${MATCHING_ROOT}/schema/matching-engine.xml)

enable_testing()

add_subdirectory(protocol)
add_subdirectory(api)
add_subdirectory(conformance)
add_subdirectory(naive)
add_subdirectory(lean)
add_subdirectory(test)
