## Purpose

Measures whether parallel chunk recalculation actually helps, and proves the
world state it produces matches the stock single-threaded pass, so the change is
accepted on evidence rather than on expectation.

## ADDED Requirements

### Requirement: Chunk recalculation is instrumented

The system SHALL record, per chunk recalculation, how long the pass took and how
long the chunk waited before it started.

#### Scenario: Chunks load during play

- **WHEN** chunks stream in during a session
- **THEN** each recalculation's duration and queue wait are recorded with the
  chunk's coordinates

#### Scenario: Instrumentation is cheap

- **WHEN** instrumentation is enabled
- **THEN** it does not measurably change the frame time it is meant to measure

### Requirement: A repeatable worst-case benchmark

The harness SHALL provide a repeatable scenario that crosses chunk boundaries
continuously, since that is when recalculation throughput limits the game.

#### Scenario: Running the benchmark

- **WHEN** the operator runs the benchmark against a fixed save and a fixed route
- **THEN** it reports chunk-load throughput, and the distribution of frame times
  including the worst percentiles, not just the mean

#### Scenario: Repeat runs agree

- **WHEN** the benchmark is run twice against the same save and route on an
  otherwise idle machine
- **THEN** the reported figures agree closely enough to tell a real improvement
  from run-to-run noise

### Requirement: Before-and-after comparison

The harness SHALL compare a stock run against an overridden run and report the
difference.

#### Scenario: Comparing runs

- **WHEN** the operator compares a stock run with an overridden run
- **THEN** the report states the change in chunk-load throughput and in
  worst-percentile frame time, and whether the difference exceeds measurement
  noise

#### Scenario: No improvement

- **WHEN** the overridden run is not faster
- **THEN** the report says so plainly rather than presenting a neutral result as
  a win

### Requirement: World-state parity is verifiable

The harness SHALL be able to prove that parallel recalculation produced the same
world state as the single-threaded pass.

#### Scenario: Capturing and comparing world state

- **WHEN** the operator captures world state after loading a fixed set of chunks
  single-threaded, then again with recalculation parallelised
- **THEN** the two captures are compared and any differing square is reported
  with its coordinates and the fields that differ

#### Scenario: States match

- **WHEN** the two captures are identical
- **THEN** the harness reports parity, and this is a precondition for accepting
  the change
