# Development roadmap

## Implemented in the current development line
- Kotlin + Jetpack Compose Android shell.
- Arabic/English toggle and RTL/LTR layout.
- Driver-first dark UI with manual order lifecycle wired through the validated domain state reducer.
- Explainable restaurant-cluster scoring model and domain unit tests.
- GitHub Actions test + debug APK build workflow.
- MapLibre Native Android OpenGL map renderer with OpenFreeMap style and a 50-degree perspective camera.
- Runtime location permission request and explicit foreground GPS service with persistent notification.
- Shared text-link parsing for coordinate pairs and common Google Maps coordinate URL formats.
- OSRM driving route preview rendered as a map polyline when a destination is shared and a GPS fix is available.

## Database schema foundation (2026-11-26)
- `database/001_riyadh_places.sql`: SQLite data sources, Riyadh business branches, provenance observations, constraints, indexes and active-places view.
- `database/002_delivery_platform_orders.sql`: delivery platform registry, platform-to-branch links, privacy-minimized order observations and recent-order activity view. HungerStation is registered for user-shared Android content.
- `database/README.md`: import/export, licensing, deduplication, provenance and privacy requirements.
- SQLite syntax validation passed in an in-memory test against the existing places-table contract.
- **Not complete:** no populated verified Riyadh business dataset; no actual SQLite database file shipped; no Room entities/DAOs/repository/migrations in Android; no app UI/search integration; and no automatic HungerStation feed/API. The platform order schema accepts only user-shared content or a future authorized feed.

## Remaining before a production handoff
1. Add robust destination resolution for short links and address-only shares; add safe geocoding and user confirmation.
2. Add route steps, voice guidance, route progress, off-route detection and automatic rerouting.
3. Add camera follow/bearing smoothing and navigation-safe controls.
4. Integrate the SQLite contract into Android Room: entities, DAOs, repository, versioned migrations and migration tests.
5. Add licensed/verified Riyadh restaurant and store branch data with source manifests, deduplication and freshness labels.
6. Parse supported user-shared HungerStation content through Android share intents, map it to a known branch, and persist only minimized order observations. Do not scrape private APIs or store customer personal data.
7. Connect the local driver lifecycle to real authorized delivery/order data sources; current state transitions are manual.
8. Add overlay mode with explicit permission and safe background lifecycle handling.
9. Build the optional authenticated remote-control backend, device pairing, command expiry/idempotency, audit and offline queue.
10. Complete real-device QA, privacy review, network/battery tests and protected release signing.

No demand, spoken navigation, remote-control or order-platform integration is described as live until implemented and verified. The public OSRM demo service is not a production SLA.
