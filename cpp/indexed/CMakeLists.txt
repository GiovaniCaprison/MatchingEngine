# Rung one in this language, at a layout matched to the Java rung: sorted levels, intrusive
# queues, a name index, and the same behaviour to the byte.

add_library(matching-indexed STATIC
  auction.cpp
  indexed_engine.cpp)

target_include_directories(matching-indexed PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/..)
target_link_libraries(matching-indexed PUBLIC matching-api matching-protocol)

add_executable(differential-indexed differential_main.cpp)
target_link_libraries(differential-indexed PRIVATE matching-indexed matching-conformance)
