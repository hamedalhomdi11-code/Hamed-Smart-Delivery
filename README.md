# Hamed Smart Delivery

**حامد سمارت دليفري** — Android-first smart courier navigation and dispatch assistant.

> Current status: architecture + first interactive Android shell. This is not yet a production-ready delivery navigation app. Live map tiles/routing, GPS foreground service, floating overlay service, backend/control plane, persistent restaurant database, link extraction, and signed release APK are staged for implementation and require provider credentials/real-device validation.

## Product principles
- Arabic-first with English toggle; RTL/LTR layout.
- One dynamic driver action: find orders → I got an order → delivery state.
- Map/navigation is the core always-on capability; assistant/control server is optional.
- Remote ChatGPT control and manual touch control use the same command/state model.
- Offline-first local state; queued commands must be revalidated before execution.
- No fabricated demand estimates: distinguish public map signals from observed order history.
- Safety-first UI: minimal driver distraction and no touch-heavy workflows while moving.

## Build
Open in Android Studio (JDK 17, Android SDK 35) and build `:app:assembleDebug`.

Or use GitHub Actions (`Android CI`) after pushing this repository to a new GitHub repository. The workflow uploads the debug APK as an artifact.

## App identity
- Display name: Hamed Smart Delivery
- Package: `com.hamed.smartdelivery`
- Initial version: `0.1.0` (architecture / UI foundation)

## Repository map
See [SYSTEM_ARCHITECTURE.md](docs/SYSTEM_ARCHITECTURE.md), [ROADMAP.md](docs/ROADMAP.md), [ACCEPTED_REQUIREMENTS.md](docs/ACCEPTED_REQUIREMENTS.md), and [INTEGRATIONS.md](docs/INTEGRATIONS.md).
