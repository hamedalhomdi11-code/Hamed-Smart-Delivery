# Development roadmap

## Implemented in the current development branch
- Kotlin + Jetpack Compose Android shell.
- Arabic/English toggle and RTL/LTR layout.
- Driver-first dark UI and interactive search/order state prototype.
- Explainable restaurant-cluster scoring model and domain unit tests.
- GitHub Actions test + debug APK build workflow.
- Real OpenStreetMap map tiles rendered inside the app using osmdroid.
- Runtime foreground location permission request and OSM attribution.
- Riyadh initial map viewport; device location overlay when permission is granted.

## Remaining before a production handoff
1. Add a lifecycle-safe foreground location service with Android notification and battery handling.
2. Add destination sharing/import for HungerStation and standard Google Maps links.
3. Implement route calculation, route drawing, camera bearing/tilt, off-route handling and spoken turn guidance.
4. Add Room persistence for restaurant observations and trip summaries, excluding customer personal data.
5. Complete the actual driver state-machine wiring; the current visual order action is still a UI prototype.
6. Add overlay mode with explicit permission and safe background lifecycle handling.
7. Build the optional authenticated remote-control backend, device pairing, command expiry/idempotency, audit and offline queue.
8. Complete real-device QA, privacy review, network/battery tests and protected release signing.

No demand, navigation, remote-control or order-platform integration is described as live until it is implemented and verified. Public OSM endpoints are not a production SLA.
