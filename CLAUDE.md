# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is the **Freshpaint Android SDK**, a fork of the Segment Android SDK that provides analytics event tracking for Android applications. The SDK handles event batching, queueing to disk, and periodic uploads to Freshpaint servers.

## Build Commands

### Building the Project
```bash
# Clean build
./gradlew clean

# Build all modules
./gradlew build

# Build main analytics library (release AAR)
./gradlew :analytics:assembleRelease

# Build with release profile (for publishing)
./gradlew :analytics:bundleReleaseAar -Prelease
```

### Testing
```bash
# Run all unit tests
./gradlew test

# Run tests for analytics module only
./gradlew :analytics:test

# Run debug variant tests specifically
./gradlew :analytics:testDebugUnitTest

# Run tests for a single test class
./gradlew :analytics:testDebugUnitTest --tests "FullyQualifiedClassName"
```

### Code Quality
```bash
# Check code formatting (Spotless with Google Java Format)
./gradlew spotlessCheck

# Apply code formatting
./gradlew spotlessApply

# Run lint checks
./gradlew lint

# Run all verification checks
./gradlew check
```

### Publishing
```bash
# Publish to Sonatype Central (requires credentials)
./gradlew :analytics:publishAllPublicationsToSonatypeRepository -Prelease
```

## Project Structure

### Gradle Modules

- **analytics**: Core SDK library (`io.freshpaint.android`)
- **analytics-tests**: Test utilities and helpers
- **analytics-wear**: Android Wear OS extension
- **analytics-samples/analytics-sample**: Sample Android app
- **analytics-samples/analytics-wear-sample**: Sample Wear OS app

### Key Source Directories

```
analytics/src/main/java/io/freshpaint/android/
├── Freshpaint.java              # Main SDK entry point (singleton pattern)
├── FreshpaintIntegration.java   # Core integration handling event dispatch
├── AnalyticsContext.java        # Context info (device, app, screen, etc.)
├── Properties.java, Traits.java, Options.java  # Event metadata wrappers
├── integrations/                # Integration system
│   ├── Integration.java         # Base interface for integrations
│   ├── BasePayload.java         # Base for all event payloads
│   ├── TrackPayload.java        # Track events
│   ├── IdentifyPayload.java     # User identification
│   ├── ScreenPayload.java       # Screen tracking
│   ├── GroupPayload.java        # Group identification
│   └── AliasPayload.java        # User aliasing
└── internal/                    # Internal utilities
    ├── Utils.java               # Utility functions
    ├── Iso8601Utils.java        # Date/time formatting
    └── NanoDate.java            # High-precision timestamps
```

## Architecture

### Core Components

1. **Freshpaint** (singleton): Main SDK interface
   - Provides `with(Context)` for global singleton access
   - Builder pattern for custom configuration
   - Manages lifecycle, integrations, and event dispatch

2. **Integration System**: Pluggable architecture for event destinations
   - `Integration.Factory`: Creates integration instances
   - `Integration<T>`: Base interface with lifecycle callbacks
   - `FreshpaintIntegration`: Default integration handling Freshpaint API

3. **Event Payloads**: Strongly-typed event data
   - All inherit from `BasePayload`
   - Support middleware transformation via `MiddlewareChainRunner`
   - Serialized to JSON for network transmission

4. **Queue & Batching**:
   - `PayloadQueue`: Persistent disk-based queue using `QueueFile`
   - Events batched and uploaded periodically
   - Automatic retry with exponential backoff

5. **Context Collection**:
   - `AnalyticsContext`: Captures device info, screen properties, app version
   - `GetAdvertisingIdTask`: Async GAID collection
   - `AnalyticsActivityLifecycleCallbacks`: Automatic activity tracking

### Key Technical Details

- **Min SDK**: API 21 (Lollipop), API 25 for Wear
- **Target SDK**: API 35
- **Compile SDK**: 35
- **Java Version**: Java 17 (source & target compatibility)
- **Kotlin**: 2.0.21
- **Threading**: Uses `ExecutorService` for network operations
- **Lifecycle**: Integrates with AndroidX Lifecycle for process state tracking
- **Persistence**: Disk-based event queue survives app restarts
- **Testing**: Robolectric 3.5 for Android unit tests

## Development Notes

### Code Style
- Google Java Format enforced via Spotless plugin
- All source files must include MIT license header
- License header automatically applied by Spotless
- Future implementations will focus on migrating Java to Kotlin codebase

### Testing Strategy
- Unit tests use Robolectric for Android framework mocking
- Tests located in `analytics/src/test/java/`
- MockWebServer for HTTP testing
- AssertJ for fluent assertions

### Version Management
- Version defined in `gradle.properties`: `VERSION_NAME` and `VERSION_CODE`
- Versioning script: `gradle/versioning.gradle`
- Release process documented in `RELEASING.md`

### CI/CD
- CircleCI configuration at `.circleci/config.yml`
- Automated testing on every push
- Release builds on `main` branch and version tags
- Publishes to Sonatype Central

### Publishing Setup
Publishing requires:
- `sonatypeUsername` and `sonatypePassword` (Sonatype Central tokens)
- GPG signing via `SIGNING_KEY_B64` and `SIGNING_PASSWORD` environment variables
- Use `-Prelease` flag for release builds

## Common Workflows

### Adding a New Event Type
1. Create payload class in `integrations/` extending `BasePayload`
2. Add corresponding method to `Freshpaint` class
3. Update `FreshpaintIntegration` to handle new payload type
4. Add middleware support in `MiddlewareChainRunner`
5. Write unit tests for new functionality

### Adding an Integration
1. Implement `Integration.Factory` interface
2. Create `Integration<T>` subclass with event handlers
3. Register factory in `Freshpaint.Builder.use(Integration.Factory)`
4. Integration receives all events via typed payload methods

### Debugging Event Flow
1. Enable debug logging: `Freshpaint.Builder.logLevel(Logger.Level.VERBOSE)`
2. Events flow: Freshpaint → MiddlewareChain → Integrations → Network/Queue
3. Check queue state in app's private storage
4. Monitor network traffic to Freshpaint API endpoints
