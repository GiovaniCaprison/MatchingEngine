# Rung zero in this language, at a layout matched to the Java rung: one list for the book and one
# for the stops, an order object per command, a scan wherever a question is asked. Implementing it
# twice is what lets language separate from layout.

add_library(matching-naive STATIC
  auction.cpp
  naive_engine.cpp)

target_include_directories(matching-naive PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/..)
target_link_libraries(matching-naive PUBLIC matching-api matching-protocol)

# The rung's half of the differential test: replay a generated log and write every byte published,
# for the Java side to diff. The Java test finds this binary by its build path and abstains when it
# is absent, so building it is what arms that test.
add_executable(differential-naive differential_main.cpp)
target_link_libraries(differential-naive PRIVATE matching-naive matching-conformance)
