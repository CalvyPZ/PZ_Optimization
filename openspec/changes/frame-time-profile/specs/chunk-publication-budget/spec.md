## Purpose

Keeps a burst of newly streamed chunks from landing on the game thread in a
single frame by bounding the time spent per frame handing them into the
world — applied only if attribution shows that hand-off in the frame-time
tail.

## ADDED Requirements

### Requirement: Chunk hand-off per frame is time-bounded

When enabled, the game thread SHALL stop handing published chunks into the
world for the current frame once a configured time budget is spent, and
continue with the remainder on following frames, in the same order.

#### Scenario: Burst of published chunks

- **WHEN** more chunks are ready than fit the per-frame budget
- **THEN** the frame processes chunks until the budget is exhausted and the
  rest are processed on later frames, without reordering

#### Scenario: Chunks trickle in

- **WHEN** the number of ready chunks is small enough to fit the budget
- **THEN** every ready chunk is handed off in that frame, as today

#### Scenario: Budget is configurable and switchable

- **WHEN** the operator sets the budget, or disables it
- **THEN** the setting is reported at startup, and disabling it restores the
  stock count-based behaviour without reinstalling

### Requirement: World state is unchanged by the budget

The budget SHALL only change *when* a chunk is handed off, never what the
hand-off does; the resulting world state SHALL be identical to stock.

#### Scenario: Parity with the budget enabled

- **WHEN** the parity harness runs with the budget enabled
- **THEN** the capture matches the stock baseline

### Requirement: Applied only on evidence

The budget SHALL be shipped enabled only if attribution shows chunk hand-off
among the top contributors to the frame-time tail and the benchmark shows a
tail improvement beyond noise without a chunk-latency regression beyond
noise; otherwise it SHALL ship disabled or not at all, and the report SHALL
say which.

#### Scenario: Hand-off is not a top contributor

- **WHEN** attribution ranks chunk hand-off outside the top contributors
- **THEN** the budget is not enabled and the report records why
