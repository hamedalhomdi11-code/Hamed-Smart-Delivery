# Hamed Smart Delivery

**حامد سمارت دليفري** — Android-first smart courier navigation and dispatch assistant.

> Current development status: the driver-first Arabic/English interface is retained and the mock map has been replaced with a real OpenStreetMap (OSM) map view. The app now requests Android foreground location permission and can display the device location while the app is open. This is an integration milestone, not yet a production-ready navigation release: turn-by-turn routing, background location service, delivery-link extraction, persistent trip/restaurant storage, remote-control backend, device QA and signed release packaging remain to be completed and verified.

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
- Map rendering: OpenStreetMap tiles via osmdroid.
- Map attribution is shown in the app.
- Initial map viewport is Riyadh; user location is requested only after a user action.
- Location marker is available while the app is in the foreground and permission is granted.
- OSM tile and routing services have usage policies and public endpoints are not a guaranteed production SLA. A production routing provider and device testing are still required.

## Build
Open in Android Studio (JDK 17, Android SDK 35) and build `:app:assembleDebug`.

GitHub Actions workflow: `.github/workflows/android-ci.yml`. It runs unit tests, builds the debug APK, and uploads it as an artifact.

## App identity
- Display name: Hamed Smart Delivery
- Package: `com.hamed.smartdelivery`
- Current development version: `0.2.0`

## Repository map
See [SYSTEM_ARCHITECTURE.md](docs/SYSTEM_ARCHITECTURE.md), [ROADMAP.md](docs/ROADMAP.md), [ACCEPTED_REQUIREMENTS.md](docs/ACCEPTED_REQUIREMENTS.md), and [INTEGRATIONS.md](docs/INTEGRATIONS.md).
