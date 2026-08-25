// The instrument this engine handles, read once out of the definition command (FR-1.1). Its fields
// are trusted: reference data is validated by whatever owns it, several components upstream, so a
// nonsensical tick size is a programming error there and not a refusal here.

#pragma once

#include <cstdint>

#include "io_github_giovanicaprison_matching_protocol/AllocationAlgorithm.h"
#include "io_github_giovanicaprison_matching_protocol/InstrumentDefinition.h"

namespace io::github::giovanicaprison::matching::indexed {

struct Instrument {
  std::uint32_t id = 0;
  std::int64_t tickSize = 1;
  std::int64_t lotSize = 1;
  std::int64_t minPrice = 0;
  std::int64_t maxPrice = 0;
  std::uint8_t priceScale = 0;
  std::int64_t bandWidth = 0;
  std::int64_t openingReference = 0;
  protocol::AllocationAlgorithm::Value allocation = protocol::AllocationAlgorithm::PRICE_TIME;

  static Instrument of(protocol::InstrumentDefinition& definition) {
    Instrument instrument;
    instrument.id = definition.frame().instrumentId();
    instrument.tickSize = definition.tickSize();
    instrument.lotSize = definition.lotSize();
    instrument.minPrice = definition.minPrice();
    instrument.maxPrice = definition.maxPrice();
    instrument.priceScale = definition.priceScale();
    instrument.bandWidth = definition.bandWidth();
    instrument.openingReference = definition.openingReference();
    instrument.allocation = definition.allocation();
    return instrument;
  }
};

}  // namespace io::github::giovanicaprison::matching::indexed
