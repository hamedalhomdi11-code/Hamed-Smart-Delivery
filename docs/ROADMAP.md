# Development roadmap

## Current foundation
- Kotlin + Jetpack Compose Android shell.
- Arabic/English toggle and RTL/LTR layout.
- Interactive driver search/order state UI.
- Explainable restaurant-cluster scoring model.
- Unit tests for the state reducer and scoring.
- GitHub Actions debug build workflow.

## Next implementation phases
1. Validate and stabilise the Android build; fix compiler/test issues.
2. Add location permission onboarding and a foreground location service.
3. Integrate a licensed map provider, route calculation, camera bearing/tilt and turn guidance.
4. Add Room persistence for restaurant observations and trip summaries.
5. Implement Android share intents for HungerStation destination links.
6. Add overlay mode and safe background lifecycle handling.
7. Build authenticated remote-control backend, telemetry, command audit and offline queue.
8. Add real-device QA, battery tests, privacy review and signed release packaging.

No demand, navigation or remote-control capability is considered live until its integration is implemented and tested.
