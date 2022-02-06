## [Unreleased]

### [Live Platform](https://github.com/sourceplusplus/live-platform)

#### Changed
- Consolidate instrument remotes
- Individual subscriber events

### [Live Protocol](https://github.com/sourceplusplus/protocol)

#### Added
- `LiveVariable.presentation`

#### Changed
- Tons of refactoring

#### Removed
- Portal-only DAOs
- `Log.getFormattedMessage`
- `LiveInstrumentContext`
- `LiveInstrumentBatch`

## 0.3.1 (2022-02-04)

#### Added
- AGPLv3 license header
- Release workflow

#### Changed
- Centralized changelogs

### [Live Platform](https://github.com/sourceplusplus/live-platform)

#### Added
- Ability to set `JAVA_OPTS` environment variable
- Ability to enable/disable individual processors via configuration

#### Removed
- Log summary processor (WIP)

### [Live Protocol](https://github.com/sourceplusplus/protocol)

#### Deprecated
- `Log.getFormattedMessage()`

### [JetBrains Plugin](https://github.com/sourceplusplus/interface-jetbrains)

#### Added
- Ability to configure plugin via `.spp/spp-plugin.yml` file

#### Fixed
- Backwards compatibility issues

## [0.3.0] - 2022-01-26
- More platform modularization
- Environment configurable properties
- Added in-memory storage option

## [0.2.1] - 2021-12-02
- Code refactoring
- Dependency upgrades

## [0.2.0] - 2021-11-17
- Modularized platform
- Added Python probe
- Removed Elasticsearch requirement

## [0.1.19] - 2021-10-25
- Improved CLI default config

## [0.1.17] - 2021-10-22
- Improved JetBrains product code handling

## [0.1.15] - 2021-10-20
- Fixed `/clients` response in native build

## [0.1.14] - 2021-10-19
- Added `/clients` endpoint
- Refactored LiveInstrumentRemote
- Fixed plugin publishing workflow

## [0.1.7] - 2021-10-13
- Ability to CRUD instrument meta data via CLI/API
- Added `hit_count`, `created_at`, `created_by`, `first_hit_at`, and `last_hit_at` instrument meta attributes 
- Ability to move control bar with arrow keys
- Removed redundant location-source/location-line live breakpoint attributes

## [0.1.6] - 2021-10-07
- Ability to open control bar before/after current line
- Fixed error report configuration issue

## [0.1.5] - 2021-10-07
- Added meta storage to live instruments
- Improved status bar UIs
- Early refactoring for coming Python support

## [0.1.3] - 2021-10-02
- Ability to set system access token via spp-platform.yml
- Fixed live control bar action configuration

## [0.1.2] - 2021-09-30
- Increase IntelliJ compatability range

## [0.1.1] - 2021-09-30
- Improved live control bar
- Source mark visibility toggle shortcut (Ctrl+Shift+D)

## [0.1.0] - 2021-09-20
- First public release
