## Purpose

Installs and removes the compiled class overrides against a Project Zomboid
install, so the optimisation can be applied and fully reverted without ever
modifying the game's shipped jar.

## ADDED Requirements

### Requirement: Overrides install without modifying the shipped jar

The installer SHALL place compiled classes where the game's existing classpath
loads them in preference to the jar, and SHALL NOT write to the jar.

#### Scenario: Installing

- **WHEN** the operator installs the overrides against a game install
- **THEN** the compiled classes are present in the install directory and the
  jar's contents and checksum are unchanged

#### Scenario: Classpath does not prefer loose classes

- **WHEN** the game's launcher configuration does not place the install
  directory ahead of the jar on the classpath
- **THEN** the installer refuses to install and explains what it found

### Requirement: Uninstall restores the stock game exactly

Removing the overrides SHALL return the install to its original state.

#### Scenario: Uninstalling

- **WHEN** the operator uninstalls
- **THEN** every file the installer added is removed, and no file that existed
  beforehand has been modified

#### Scenario: Uninstall after a game update

- **WHEN** the operator uninstalls after the game has been updated
- **THEN** only files the installer recorded as its own are removed

### Requirement: Overrides refuse to run against an unexpected game build

Class overrides compiled from one game revision are unsafe against another. The
system SHALL detect a mismatch and refuse rather than run.

#### Scenario: Game updated since the overrides were built

- **WHEN** the installed game build differs from the build the overrides were
  compiled against
- **THEN** the system reports the mismatch and does not apply the overrides

#### Scenario: Matching build

- **WHEN** the installed game build matches
- **THEN** the overrides load and the game starts normally

### Requirement: Install state is inspectable

The operator SHALL be able to ask whether the overrides are installed and which
game build they were compiled against.

#### Scenario: Checking status

- **WHEN** the operator queries install status
- **THEN** the system reports whether overrides are present, which files they
  are, and the game build they target
