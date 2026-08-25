# The second reader of the corpus. It parses the same fixtures the Java runner parses and encodes
# the same bytes, which is where any remaining ambiguity in the fixture format surfaces: a format
# only one program has ever read is a format whose accidents look like rules.

add_library(matching-conformance STATIC
  command_writer.cpp
  consumer_book.cpp
  corpus.cpp
  event_reader.cpp
  fixture_parser.cpp)

target_include_directories(matching-conformance PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/..)
target_link_libraries(matching-conformance PUBLIC matching-protocol)
