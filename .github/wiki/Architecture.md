# Architecture

Codebase layout, module boundaries, and tech stack specification.

---

## Architecture Overview

Hikaru follows **Clean Architecture** principles, decoupling domain logic, data sources, and presentation across isolated Gradle modules.

```
Hikaru/
├── app/                    # Application entry point, Koin DI bindings, Voyager Navigation, tracker implementations
├── core/                   # Shared utilities and core submodules (:core:common, :core:archive)
├── core-metadata/          # Metadata parsers and serialization schemas
├── data/                   # Repository implementations, SQLDelight database, OkHttp networking
├── domain/                 # Business logic, use cases, pure Kotlin models
├── presentation-core/      # Jetpack Compose UI components, design system tokens, themes
├── presentation-widget/    # Home screen widget components
├── source-api/             # Extension interfaces and source abstractions
├── source-local/           # Local media indexing and storage access
├── telemetry/              # Analytics and crash reporting integration
└── i18n/                   # String resources and localization modules (:i18n, :i18n-kmk, :i18n-sy)
```

---

## Technical Stack

- **Language**: Kotlin with Coroutines and `StateFlow`/`SharedFlow` for asynchronous state management.
- **UI Framework**: Jetpack Compose using Material Design 3 guidelines.
- **Navigation**: Voyager multi-stack navigation framework.
- **Database**: SQLDelight for type-safe SQLite database operations.
- **Dependency Injection**: Koin.
- **Media Playback**: `mpv-android` (`libmpv` C bindings) with custom GLSL shader support.

---

## Module Boundary Rules

- **`domain`**: Pure Kotlin module without Android framework (`android.*`) dependencies.
- **`data`**: Implements repository interfaces defined in `domain` using SQLDelight and OkHttp.
- **`presentation-core`**: Consumes `domain` models and use cases, exposing UI state via `StateFlow`.

