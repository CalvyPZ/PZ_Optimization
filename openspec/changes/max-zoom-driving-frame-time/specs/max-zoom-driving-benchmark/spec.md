## Purpose

Provides a reproducible measurement of the frame-time behavior players experience while driving at maximum zoom, so renderer optimizations are selected from the real scenario rather than from a teleport-based proxy.

## ADDED Requirements

### Requirement: Benchmark uses real vehicle movement

The benchmark SHALL run from a fixed save in which the player remains in a vehicle and SHALL drive the route through the game's vehicle movement path rather than teleporting the player between tiles.

#### Scenario: Maximum-zoom driving route

- **WHEN** the operator runs the maximum-zoom driving benchmark
- **THEN** the player remains in the vehicle for the measured route, the effective zoom is the configured maximum, and the route completes through vehicle movement or a recorded input replay

#### Scenario: Benchmark setup is invalid

- **WHEN** the required vehicle, driver, or maximum-zoom state cannot be established
- **THEN** the run fails explicitly and is excluded from comparison results

### Requirement: Run records the rendering scenario

Each benchmark run SHALL record the effective zoom, offscreen render dimensions, driving-camera-pan state, chunk-map width, display resolution, renderer/backend, and whether the dashboard mod is enabled.

#### Scenario: Comparable run metadata

- **WHEN** a run completes or fails
- **THEN** its metadata identifies every scenario variable needed to determine whether it is comparable with another run

### Requirement: Attribution identifies the dominant frame contributors

The benchmark SHALL report frame-time percentiles and per-frame attribution for rendering, translucent work, cutaway/occlusion work, lighting updates, vehicle physics, and Lua work, with p99, p99.9, spike counts, and the measured noise floor.

#### Scenario: Controlled A/B comparison

- **WHEN** the operator compares maximum zoom with 100% zoom, camera pan enabled with disabled, or dashboard enabled with disabled
- **THEN** the report shows each variant's frame-tail metrics and states whether the difference exceeds the baseline noise floor

#### Scenario: Profiling overhead is material

- **WHEN** a profiling mode changes the frame distribution beyond the established noise floor
- **THEN** that mode is not used as the primary measurement and its overhead is reported explicitly
