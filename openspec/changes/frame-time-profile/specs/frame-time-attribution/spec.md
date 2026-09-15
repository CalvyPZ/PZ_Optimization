## Purpose

Tells us, with evidence rather than guesswork, what the game thread is doing
during the frames that take longest on the fixed benchmark route, so any
frame-time fix targets the real cause.

## ADDED Requirements

### Requirement: Slow frames are attributed to code

The harness SHALL produce, for a benchmark run, a breakdown of what the game
thread was executing during slow frames, separately from ordinary frames.

#### Scenario: Profiled benchmark run

- **WHEN** the operator runs the benchmark with profiling enabled
- **THEN** the run yields, for frames above a chosen threshold (default
  20 ms), a ranked list of the code paths sampled inside them, each with its
  share of slow-frame time, and the same list for ordinary frames for contrast

#### Scenario: Profiling does not distort the measurement

- **WHEN** a profiled run is compared with the unprofiled stock baseline
- **THEN** the frame-time distribution differs by no more than the
  run-to-run noise floor, or the report states the overhead explicitly

### Requirement: Candidate contributors are A/B tested

The harness SHALL let the operator compare the benchmark with one suspected
contributor removed or swapped, with no code change, and report the
difference against noise.

#### Scenario: Per-tick mod disabled

- **WHEN** the benchmark is run with the per-tick Lua mod disabled
- **THEN** the report states the change in frame-time tail and whether it
  exceeds noise

#### Scenario: Garbage collector swapped

- **WHEN** the benchmark is run with the alternative collector the game ships
  configured for
- **THEN** the report states the change in frame-time tail, the collector's
  pause events during the route, and whether the difference exceeds noise

### Requirement: The attribution is ranked and honest

The result SHALL be a ranked list of contributors to the frame-time tail with
their measured shares, including "unexplained" when samples do not account
for the time, and SHALL say when no single contributor dominates.

#### Scenario: One dominant cause

- **WHEN** one code path accounts for most of the slow-frame samples
- **THEN** the report names it first with its share and the runs that show it

#### Scenario: No dominant cause

- **WHEN** slow-frame time is spread across many paths
- **THEN** the report says so rather than promoting the largest sliver to a
  cause
