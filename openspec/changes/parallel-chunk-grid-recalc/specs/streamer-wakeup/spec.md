## Purpose

Makes the world streamer react to newly queued chunks immediately instead of
noticing them on a fixed sleep cadence, since that cadence — not the
recalculation work — is most of the time a chunk waits before it appears.

## ADDED Requirements

### Requirement: The streamer wakes when work is enqueued

The streamer thread SHALL begin processing a newly enqueued chunk without
waiting out a fixed sleep, while behaving as stock when nothing is enqueued.

#### Scenario: Chunk enqueued while the streamer is idle

- **WHEN** the streamer's job list is empty and a chunk is enqueued
- **THEN** the streamer starts loading it within a few milliseconds rather than
  at the next 140 ms poll

#### Scenario: Nothing enqueued

- **WHEN** no chunk is enqueued
- **THEN** the streamer idles with no more wake-ups than the stock loop
  (one every 140 ms at most)

#### Scenario: Burst of chunks

- **WHEN** a row of chunks is enqueued in one frame
- **THEN** the streamer processes the whole burst back to back with no sleep
  between chunks or after the last one

### Requirement: Wake-up is switchable at runtime

The system SHALL let the operator disable the wake-up and fall back to the
stock sleep cadence without reinstalling, independently of the recalc pool
setting, so the two effects can be measured separately.

#### Scenario: Wake-up disabled

- **WHEN** the operator disables the wake-up and starts the game
- **THEN** the streamer polls with the stock 140 ms sleeps
