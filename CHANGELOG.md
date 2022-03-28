## [Unreleased]

## 0.4.4 (2022-03-28)

### [JetBrains Plugin](https://github.com/sourceplusplus/interface-jetbrains)

#### Added
- Chinese (Simplified) language localization

#### Changed
- Updated icons and improved command palette UI

### [Live Portal](https://github.com/sourceplusplus/interface-portal)

#### Added
- Internationalization support
- Improved UI

#### Fixed
- Issue showing span tag/logs on multi-segment traces
- Portal server properly closes during configuration changes

### [Live Protocol](https://github.com/sourceplusplus/protocol)

#### Added
- Internationalization support

### [Live View Processor](https://github.com/sourceplusplus/processor-live-view)

#### Added
- Include `resolvedEndpointName` in Trace metadata

## 0.4.3 (2022-03-19)

### [JetBrains Plugin](https://github.com/sourceplusplus/interface-jetbrains)

#### Added
- `Show/Hide Quick Stats` commands
- Implemented LiveViewService for SkyWalking-only installations
- Auto-display quick stats setting

#### Changed
- Moved BigInteger/Class live variable presentation to instrument processor
- Default auto-resolve endpoint names

#### Fixed
- Compatability issues with IntelliJ 221.4994.44+

### [JVM Probe](https://github.com/sourceplusplus/probe-jvm)

#### Added
- Additional default SkyWalking properties to standalone installation

### [Live Protocol](https://github.com/sourceplusplus/protocol)

#### Added
- `SHOW_QUICK_STATS` role permission
- CommandType enum to distinguish RolePermissions

### [Live CLI](https://github.com/sourceplusplus/interface-cli)

#### Changed
- SubscribeView outputs entityName instead of entityId

### [Live Instrument Processor](https://github.com/sourceplusplus/processor-live-instrument)

#### Added
- BigInteger/Class to automatic live variable presentation

### [Live View Processor](https://github.com/sourceplusplus/processor-live-view)

#### Changed
- Output events with entity name as well as entity id

## 0.4.2 (2022-03-14)

### [JetBrains Plugin](https://github.com/sourceplusplus/interface-jetbrains)

#### Added
- Added `Watch Log` command
- Activity quick stats inlay hints for method endpoints

#### Changed
- Use `ProtocolMarshaller` instead of default marshaller for protocol messages

#### Fixed
- Live log template positioning issue

#### Removed
- Unused code/modules
- Hardcoded config

### [JVM Probe](https://github.com/sourceplusplus/probe-jvm)

#### Added
- Added depth capping to live variable serialization
- Added async executor to `ContextReceiver`

#### Changed
- Use `ProtocolMarshaller` instead of default marshaller for protocol messages

### [Live Platform](https://github.com/sourceplusplus/live-platform)

#### Added
- Ability to reroute to SkyWalking on `/graphql` with `spp-skywalking-reroute` header

#### Changed
- Use `ProtocolMarshaller` instead of default marshaller for protocol messages
- Send `DeveloperAuth` instead of `InstanceConnection` on marker disconnection

### [Live Protocol](https://github.com/sourceplusplus/protocol)

#### Changed
- Simplified and improved marshalling protocol messages

#### Fixed
- Publish instead of sending messages over TCP without reply address

### [Live Portal](https://github.com/sourceplusplus/interface-portal)

#### Changed
- Generalized and moved `PortalConfiguration` to protocol
- Moved portal configuration code from IDE plugin to portal

### [Live CLI](https://github.com/sourceplusplus/interface-cli)

#### Added
- Quick install setup scripts for Linux and Windows 

#### Changed
- Use `ProtocolMarshaller` instead of default marshaller for protocol messages
- Modified long format commands to operate as subcommands

### [Live Instrument Processor](https://github.com/sourceplusplus/processor-live-instrument)

#### Added
- Automatic live variable formatting for common types via `LiveVariable.presentation` field

#### Changed
- Use `ProtocolMarshaller` instead of default marshaller for protocol messages

### [Live View Processor](https://github.com/sourceplusplus/processor-live-view)

#### Changed
- Use `ProtocolMarshaller` instead of default marshaller for protocol messages
- Send all events on multi metric subscriptions

#### Fixed
- Clear view subscriptions on marker disconnection

## 0.4.1 (2022-02-09)

### [Live Protocol](https://github.com/sourceplusplus/protocol)

#### Added
- Ability to build on Windows

### [JetBrains Plugin](https://github.com/sourceplusplus/interface-jetbrains)

#### Fixed
- NPE fix (#659)

#### Removed
- Internal Groovy plugin dependency in `LoggerDetector`

## 0.4.0 (2022-02-07)

### [Live Platform](https://github.com/sourceplusplus/live-platform)

#### Changed
- Consolidate instrument remotes
- Individual subscriber events

### [Live Instrument Processor](https://github.com/sourceplusplus/processor-live-instrument)

#### Added
- JWT authentication

### [Live View Processor](https://github.com/sourceplusplus/processor-live-view)

#### Added
- JWT authentication

### [Live Portal](https://github.com/sourceplusplus/interface-portal)

#### Added
- Portal-only DAOs

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

### [JetBrains Plugin](https://github.com/sourceplusplus/interface-jetbrains)

#### Removed
- Statically linked Vertx discovery library

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
