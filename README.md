# Hamed Smart Delivery

**حامد سمارت دليفري** — Android-first smart courier navigation and dispatch assistant.

> Current development status: the original driver-first Arabic/English interface is retained. The map uses MapLibre Native with an OpenFreeMap style and a 50-degree tilted camera. The app requests foreground location permission, starts an explicit user-controlled Android location foreground service, accepts shared coordinate links from other apps, and draws OSRM driving routes with maneuver steps, Arabic text-to-speech prompts, and best-effort off-route recalculation for imported destinations. Version 0.3.0 is still a development candidate, not a production-ready final release: automatic restaurant-cluster discovery and routing, delivery-stage destination handoff, persistent trip/restaurant storage, production routing service, optional remote-control backend, device QA and protected release signing remain.

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
- Location: explicit foreground/coarse/fine permission flow and a user-started foreground GPS service with an ongoing notification.
- Shared destination import: coordinates and common full Google Maps coordinate links.
- Route guidance: OSRM public demo endpoint with maneuver steps and location-based spoken instruction progression. Off-route detection uses an 85 m threshold and a 20 s reroute cooldown. This is best-effort development routing, not a production SLA; service availability, routing coverage and usage limits are external.
- Imported destination only: a shared coordinate link can start navigation. Automatic restaurant-cluster selection and wiring each manual delivery stage to a destination are not implemented yet.

## Build
Open in Android Studio (JDK 17, Android SDK 35) and build `:app:assembleDebug`.

GitHub Actions workflow: `.github/workflows/android-ci.yml`. It runs unit tests, builds the debug APK, and uploads it as an artifact.

## App identity
- Display name: Hamed Smart Delivery
- Package: `com.hamed.smartdelivery`
- Current development candidate: `0.3.0` (versionCode 3)

## Repository map
See [SYSTEM_ARCHITECTURE.md](docs/SYSTEM_ARCHITECTURE.md), [ROADMAP.md](docs/ROADMAP.md), [ACCEPTED_REQUIREMENTS.md](docs/ACCEPTED_REQUIREMENTS.md), and [INTEGRATIONS.md](docs/INTEGRATIONS.md).
