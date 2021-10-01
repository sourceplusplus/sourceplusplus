# ![](.github/media/sourcepp_logo.svg)

[![License](https://img.shields.io/github/license/sourceplusplus/live-platform)](LICENSE)
![GitHub release](https://img.shields.io/github/v/release/sourceplusplus/live-platform?include_prereleases)
[![Build](https://github.com/sourceplusplus/live-platform/actions/workflows/debian-build.yml/badge.svg)](https://github.com/sourceplusplus/live-platform/actions/workflows/debian-build.yml)

Source++ is an open-source live coding platform. Add breakpoints, logs, metrics, and distributed tracing to live production software in real-time on-demand, right from your IDE or CLI.

Powered by [Apache SkyWalking](https://github.com/apache/skywalking), Source++ enhances the software development experience with production debugging and development capabilities. Become a production-aware developer, understand code faster and deeper with developer-native observability technology, safely debug production applications with negligible to minimal overhead, and gain continuous insight into your application as it behaves in its natural environment.

### Features

- Live Instruments
  - **Live Breakpoints**: Non-Breaking Breakpoints 
  - **Live Logs**: Just-in-Time Logging
  - **Live Metrics**: Real-Time KPI Monitoring
  - **Live Spans**: User-Domain Tracing
- Multi-instance debugging
- Role-based access control
- Instrument conditionals
- Instrument TTL, sampling, rate limiting
- Feedback whitelist/blacklist
- PII redaction

## Architecture

![](.github/media/sourcepp_architecture.jpg)

## Get Started <!-- test -->

<!-- - [Get Source++](https://sourceplusplus.com/get/) -->
- [Tutorial app](https://github.com/sourceplusplus/tutorial-java) (Java)
- [Installation guides](https://docs.sourceplusplus.com/installation/)
- Clients
  - [JetBrains Plugin](https://docs.sourceplusplus.com/implementation/tools/clients/jetbrains-plugin/)
  - [Admin CLI](https://docs.sourceplusplus.com/implementation/tools/clients/cli/admin/) / [Developer CLI](https://docs.sourceplusplus.com/implementation/tools/clients/cli/developer/)
- Probes
  - [JVM](https://docs.sourceplusplus.com/implementation/tools/probe/general/)
  - Python (coming soon)

## Compiling Project

Follow this [document](https://github.com/sourceplusplus/documentation/blob/master/docs/guides/How-to-build.md).

## Documentation

The Source++ documentation is available [here](https://docs.sourceplusplus.com).

## Directory Structure

    .
    ├── config              # Detekt, etc.
    ├── docker              # Docker setup files
        ├── e2e             # End-to-end testing environment
        ├── spp-oap-server  # SkyWalking OAP (incl. Source++ processor) image
        └── spp-platform    # Live coding server image
    ├── documentation       # Documentation
    ├── gradle              # Gradle wrapper
    ├── interfaces          # Live coding clients
        ├── cli             # Command-line interface
        └── marker          # IDE plugin
    ├── platform            # Live coding server
    ├── probe               # Live coding JVM agent
    ├── processor           # Live instrument processing
    └── protocol            # Communication protocol

## License

This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version. Please see the [LICENSE](LICENSE) file in our repository for the full text.
