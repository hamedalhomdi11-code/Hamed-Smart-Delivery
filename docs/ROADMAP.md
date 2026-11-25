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

## Remaining before a production handoff
1. Add robust destination resolution for short links and address-only shares; add safe geocoding and user confirmation.
2. Add route steps, voice guidance, route progress, off-route detection and automatic rerouting.
3. Add camera follow/bearing smoothing and navigation-safe controls.
4. Add Room persistence for restaurant observations and trip summaries, excluding customer personal data.
5. Connect the local driver lifecycle to real delivery/order data sources; the current state transitions are manual and do not read private delivery-platform APIs.
6. Add overlay mode with explicit permission and safe background lifecycle handling.
7. Build the optional authenticated remote-control backend, device pairing, command expiry/idempotency, audit and offline queue.
8. Add real restaurant/business data sources and transparent freshness/confidence labels; do not infer or fabricate demand.
9. Complete real-device QA, privacy review, network/battery tests and protected release signing.

No demand, spoken navigation, remote-control or order-platform integration is described as live until implemented and verified. The public OSRM demo service is not a production SLA.
