# Scenario Format

The fixture grammar used by the scenario corpus in `src/test/resources/scenarios`. This file is
the contract: an implementation in any language that reads this grammar and produces this output
can be checked against the same corpus, which is the point of having one.

A scenario is two files sharing a stem. `<name>.input` is a command sequence and
`<name>.expected` is the blessed output. The stem carries the requirement id it covers, so
`fr_3_2_time_priority_fifo.input` covers FR-3.2. The test factory discovers pairs by extension,
so adding a scenario means adding two text files.

## Lexical rules

Both files are UTF-8 text, one directive per line. A line whose first non-blank character is `#`
is a comment, and blank lines are ignored, in both files, so an `.expected` file can be annotated
without affecting the comparison. There are no trailing comments: `#` is also the order reference
sigil and one character cannot be both. Fields are separated by any run of spaces, so columns can
be aligned for readability.

Order references are written `#n`, where `n` counts `NEW` directives from 1 in the order they
appear. A reference is not an engine order id. Engine ids are an implementation's own business,
and a corpus that asserted them would be testing id allocation rather than matching.

## Input directives

```
INSTRUMENT tick=<n> lot=<n> scale=<n>
```

Optional, and if present must be the first directive. Defaults are `tick=1 lot=1 scale=4`.

```
NEW <BUY|SELL> <LIMIT|MARKET|IOC|FOK|POST> <price|-> <qty>
```

Submits an order. Price is a scaled integer, or `-` for a market order, which has no price of its
own. Each `NEW` takes the next reference number whether it is accepted or refused, so references
stay stable when a fixture is edited above them.

```
CANCEL #<n>
AMEND  #<n> <qty> <price>
```

Cancels or amends a previously submitted order. `AMEND` carries the full new state rather than a
delta, matching the engine's own API.

## Expected output

Output is a line per observable event, in the order the engine produced them, followed by the
final book. A command's trades are printed before its result line, because the trades happen
during the command.

```
TRADE seq=<n> aggressor=#<n> resting=#<n> price=<n> qty=<n>
```

One execution. `seq` is its position in the engine's total order, which makes replay checkable
rather than merely plausible. `price` is the resting order's price, since price improvement
accrues to the aggressor.

```
ACCEPTED  #<n> <FILLED|RESTED|REMAINDER_CANCELLED>
REJECTED  #<n> <reason>
CANCELLED #<n>
AMENDED   #<n> <outcome>
NOTFOUND  #<n>
```

The result of a command. Reasons and outcomes are the engine's own constant names.

```
BOOK BID empty
BOOK BID <price> qty=<n>
BOOK ASK <price> qty=<n>
```

The final book, bids first then asks, best price first within a side, one line per price level
with the aggregated resting quantity at that price. A side holding nothing prints `empty`. The
serialiser reads at most 100 levels per side, so a fixture that needs a deeper book than that is
out of scope for this format.

## Re-blessing

When output changes legitimately, the runner prints the full actual output in its failure message
so it can be pasted into the `.expected` file. Read the diff before you do that. A blessed
snapshot is only worth what the last person to look at it was paying attention to.
