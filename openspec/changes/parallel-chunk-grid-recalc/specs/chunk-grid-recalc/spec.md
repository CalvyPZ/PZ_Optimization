## Purpose

Lets the game recalculate the grid squares of several streaming chunks at the
same time across multiple CPU cores, while producing exactly the world state the
stock single-threaded pass would have produced.

## ADDED Requirements

### Requirement: Concurrent recalculation of independent chunks

The system SHALL recalculate the grid squares of multiple streaming chunks
concurrently, using a worker pool whose width is derived from the number of
available processors.

#### Scenario: Multiple chunks queued on a multi-core machine

- **WHEN** more than one chunk is waiting to be recalculated and at least two
  processors are available
- **THEN** more than one chunk is in its recalculation pass at the same time

#### Scenario: Single-core machine

- **WHEN** the host reports one available processor
- **THEN** the system recalculates chunks one at a time, behaving as the stock
  game does

#### Scenario: Pool width is bounded

- **WHEN** the worker pool is created
- **THEN** its width is at least 1 and never exceeds the number of available
  processors, leaving at least one processor for the render thread

### Requirement: Output parity with the single-threaded pass

The recalculated world state SHALL be identical to the state the stock
single-threaded recalculation produces for the same chunks loaded from the same
save, for every square property the pass writes.

#### Scenario: Parity check over a loaded region

- **WHEN** a fixed set of chunks is loaded once with recalculation parallelised
  and once with it forced single-threaded
- **THEN** every grid square's recalculated properties, collision, pathfind,
  vision-blocking, roof and navigation state compare equal between the two runs

#### Scenario: Parity holds across repeated parallel runs

- **WHEN** the same fixed set of chunks is loaded twice with recalculation
  parallelised
- **THEN** the two resulting world states compare equal, so the output does not
  depend on thread scheduling

### Requirement: Chunks are published in submission order

The streamer pass touches only the chunk it was given, so concurrent passes
cannot interact; what the game thread must still see is the stock sequence.
The system SHALL hand recalculated chunks to the game thread in the order the
streamer submitted them, regardless of the order in which workers finish.

#### Scenario: Later chunk finishes first

- **WHEN** chunk B, submitted after chunk A, finishes its pass before A does
- **THEN** B is not handed to the game thread until A has been

#### Scenario: Pass touches only its own chunk

- **WHEN** a chunk is recalculated on a worker
- **THEN** no square outside that chunk is read or written by the pass

### Requirement: No shared mutable state across recalculation workers

The state a recalculation pass binds for the chunk it is processing SHALL be
private to that pass, so that two passes running at once cannot observe or
overwrite each other's binding.

#### Scenario: Two passes running at once

- **WHEN** two workers are each recalculating a different chunk
- **THEN** each worker's square lookups resolve against its own chunk, and
  neither observes the other's

### Requirement: Main-thread hand-offs stay serialized

Work the game restricts to the game thread or the server main thread SHALL
continue to run there and SHALL NOT be reached from a pool worker.

#### Scenario: Pathfinding registration

- **WHEN** a chunk finishes recalculating on a pool worker
- **THEN** its registration with the pathfinding map happens on the game thread
  or server main thread, not on the worker

#### Scenario: Worker attempts a main-thread-only call

- **WHEN** a pool worker reaches code gated to the game thread
- **THEN** the system fails loudly in development builds rather than proceeding

### Requirement: Parallelism can be disabled at runtime

The system SHALL provide a way to force single-threaded recalculation without
reinstalling or removing the overrides, so a player or tester can isolate the
change when diagnosing a problem.

#### Scenario: Parallelism disabled

- **WHEN** the operator disables parallel recalculation and starts the game
- **THEN** chunks recalculate one at a time and the game behaves as stock

#### Scenario: Setting is discoverable

- **WHEN** the operator inspects the installed overrides
- **THEN** the switch and its current value are reported

### Requirement: Failure in a worker does not corrupt the world

A recalculation that throws SHALL NOT leave a chunk partially recalculated and
silently in use.

#### Scenario: Worker throws mid-pass

- **WHEN** a recalculation pass throws an exception
- **THEN** the error is logged with the chunk coordinates, and the chunk is
  either fully recalculated by a retry on the streaming thread or not marked
  ready
