# Hamed Smart Delivery

**حامد سمارت دليفري** — Android-first smart courier navigation and dispatch assistant.

> Current development status: the original driver-first Arabic/English interface is retained. The map now uses MapLibre Native with an OpenFreeMap style and a 50-degree tilted camera. The app requests foreground location permission, starts an explicit user-controlled Android location foreground service, accepts shared coordinate links from other apps, and can draw a driving route using OSRM. This is an integration milestone, not yet a production-ready navigation release: spoken turn guidance, route rerouting, persistent trip/restaurant storage, live restaurant discovery/demand feeds, optional remote-control backend, device QA and protected release signing remain.

## Product principles
- Arabic-first with English toggle; RTL/LTR layout.
- One dynamic driver action: find orders → I got an order → delivery state.
- Map/navigation is the core capability; remote assistant/control server is optional.
- Remote commands and manual touch control use the same validated state model.
- Offline-first local state; queued commands must be revalidated before execution.
- No fabricated demand estimates: distinguish public map signals from observed order history.
- Safety-first UI: minimal driver distraction and no touch-heavy workflows while moving.
- No customer personal data is retained.

## Maps and location
- Map renderer: MapLibre Native Android (OpenGL renderer).
- Style/tiles: OpenFreeMap Liberty style, with map attribution enabled and a visible OSM credit.
- Camera: 50-degree tilt to provide a perspective navigation view.
- Location: explicit foreground/coarse/fine permission flow and a user-started foreground GPS service with an ongoing notification. The service publishes live coordinate fixes to the map UI, tracks both GPS and network providers when available, and persists the active-session flag across activity recreation.
- Shared destination import: coordinates and common full Google Maps coordinate links.
- Route preview: OSRM public demo endpoint. It is best-effort for development and is not a production SLA; service availability, routing coverage and usage limits are external.

## Build
Open in Android Studio (JDK 17, Android SDK 35) and build `:app:assembleDebug`.

GitHub Actions workflow: `.github/workflows/android-ci.yml`. It runs unit tests, builds the debug APK, and uploads it as an artifact.

## App identity
- Display name: Hamed Smart Delivery
- Package: `com.hamed.smartdelivery`
- Current development version: `0.4.3` (versionCode `43`)

## Repository map
See [SYSTEM_ARCHITECTURE.md](docs/SYSTEM_ARCHITECTURE.md), [ROADMAP.md](docs/ROADMAP.md), [ACCEPTED_REQUIREMENTS.md](docs/ACCEPTED_REQUIREMENTS.md), and [INTEGRATIONS.md](docs/INTEGRATIONS.md).
