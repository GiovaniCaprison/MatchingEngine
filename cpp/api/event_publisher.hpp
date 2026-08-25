// Where an engine writes the events it produces. The engine claims space, encodes into it and says
// when the write is done; the contract is the Java interface's, all of it a precondition rather
// than a check (P-14): one claim outstanding at a time, writes between the offset returned and that
// offset plus the length claimed, and the same buffer for the life of the publisher, so an engine
// may hold the pointer. claim waits rather than failing, because a publisher with no room is back
// pressure and an engine that could drop an event is one whose output cannot rebuild a book.

#pragma once

#include <cstddef>

namespace io::github::giovanicaprison::matching::api {

class EventPublisher {
 public:
  virtual ~EventPublisher() = default;

  // Space for one event: the offset in buffer() to encode at.
  virtual std::size_t claim(std::size_t length) = 0;

  // The buffer claims are made in. The same one every time.
  virtual char* buffer() = 0;

  // Publishes the outstanding claim. The event is a consumer's to read from here.
  virtual void commit() = 0;
};

}  // namespace io::github::giovanicaprison::matching::api
