# Rung two in this language, at a layout matched to the Java rung: the indexed shape with the
# allocation taken out, the same behaviour to the byte, and a probe whose allocator refuses after
# initialisation (NFR-4.3).

add_library(matching-pooled STATIC
  auction.cpp
  pooled_engine.cpp)

target_include_directories(matching-pooled PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/..)
target_link_libraries(matching-pooled PUBLIC matching-api matching-protocol)

add_executable(differential-pooled differential_main.cpp)
target_link_libraries(differential-pooled PRIVATE matching-pooled matching-conformance)

add_executable(allocation-probe-pooled allocation_probe_main.cpp)
target_link_libraries(allocation-probe-pooled PRIVATE matching-pooled matching-conformance)
