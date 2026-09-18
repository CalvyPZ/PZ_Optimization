## Purpose

Reduces repeated main-thread work for translucent world objects at wide camera views while preserving the stock ordering, visibility, cutaway, and dynamic-object behavior.

## ADDED Requirements

### Requirement: Translucent objects render in stock order

The renderer SHALL produce the same translucent-object ordering and visibility result as the uncached path for the same world, camera, lighting, and cutaway state.

#### Scenario: Cached translucent render

- **WHEN** a translucent render list is reused for a frame
- **THEN** objects are submitted in the same world order as the uncached renderer and camera visibility, level visibility, and cutaway checks still apply

#### Scenario: Duplicate source membership

- **WHEN** a square appears in more than one translucent, item, or cutaway source list
- **THEN** it is rendered once for the relevant pass, in the same position it would have occupied in the uncached ordered list

### Requirement: Cache is reused only while valid

The renderer SHALL reuse a prepared translucent list across camera movement, but SHALL rebuild or invalidate it when source membership or render-state changes can alter the list.

#### Scenario: Camera moves without content changes

- **WHEN** the camera or vehicle moves and the translucent source lists and cutaway membership are unchanged
- **THEN** the existing ordered list is reused without rebuilding or sorting it

#### Scenario: Content or cutaway state changes

- **WHEN** an item, translucent object, cutaway window frame, or relevant cutaway membership is added, removed, or changes render classification
- **THEN** the affected list is invalidated before the next frame that uses it

### Requirement: Cache can be disabled safely

The cache SHALL have a runtime kill switch, and disabling it SHALL restore the uncached behavior without changing world state or requiring a different game installation.

#### Scenario: Kill switch disabled

- **WHEN** the operator disables the translucent cache
- **THEN** rendering uses the stock list construction path and the setting is reported in the run metadata

#### Scenario: Visual or parity regression

- **WHEN** validation detects a missing, duplicated, reordered, or incorrectly hidden translucent object
- **THEN** the cache is disabled by default and the report records the failed validation
