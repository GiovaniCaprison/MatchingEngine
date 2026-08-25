# The three-method boundary, as headers. It says nothing about how a command is read or an event
# written, because that is the thing being compared.

add_library(matching-api INTERFACE)
target_include_directories(matching-api INTERFACE ${CMAKE_CURRENT_SOURCE_DIR}/..)
