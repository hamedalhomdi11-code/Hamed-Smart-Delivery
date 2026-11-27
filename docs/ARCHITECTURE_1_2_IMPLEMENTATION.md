# Hamed Smart Delivery — Architecture 1 & 2 Implementation Contract

Status: accepted scope for implementation; not a claim of completed functionality.

## Architecture 1 — behavior
- GPS control is one four-step cycle: tracking with a car marker; navigation with a direction arrow; stop; return to tracking. Tracking and navigation presentation must never be active together.
- Search workflow has exactly three user actions: Find orders; I got an order from this cluster; Delivery task completed. On order received, stop automatic cluster search and all waiting/switch timers. On completion, restart search automatically.
- Rank restaurant clusters using distance, restaurant density/activity, observed order history, freshness, and available signals. Do not claim guaranteed demand.
- Start the five-minute wait only after the driver enters the selected cluster's geographic radius.
- If no order arrives in five minutes, temporarily exclude that cluster for 15 minutes and automatically select another suitable cluster.
- Manual Switch cluster excludes the current cluster for 10 minutes.
- Reset cluster exclusions clears temporary exclusions only; it must preserve visit history.
- Do not repeat a visited cluster until four other clusters have been visited, except that a cluster that produced an order may be selected again after delivery when it is best/nearest. Temporary exclusions still apply.
- Record order-producing cluster ID, coordinates, timestamp, and order count in a new independent local data store. Do not alter the existing Riyadh places database or Hamed Maps server.
- Remove legacy restaurant/customer delivery-stage controls from the main driver workflow.
- Preserve the existing application ID, release signing identity, and user data. Never substitute a new signing key.

## Architecture 2 — presentation
- The map occupies the dominant portion of the driving screen; controls are compact floating controls and must not split the screen into equal map/control panels.
- Tracking uses a smoothly moving car marker. Navigation uses a smoothly moving direction arrow; never show both markers simultaneously.
- Route line has sequential, gently animated directional arrows.
- Route segments use blue for open traffic, orange for moderate/light congestion, and red for severe congestion. Only show traffic colors when a real traffic source is available; otherwise show a neutral route rather than fabricated traffic.
- Recalculate the route when the driver deviates or a better route/traffic change is confirmed; transition smoothly to the new route and keep directional arrows moving.
- When route changes, play a clear audible alert and synchronized vibration in both tracking and navigation modes. Provide controls for sound/vibration preferences.
- Motion and graphics must remain smooth and avoid unnecessary battery/performance cost.
- No traffic-color legend/buttons are required.

## Release gates
- Run unit tests for reducers, GPS cycle, cluster exclusions, wait timers, visit-repeat rules, and order event persistence.
- Build debug APK and verify installation/startup on a real Android device.
- Verify release APK is signed with the same original keystore certificate before distributing it.
- Verify map routing, location permission behavior, route recalculation, sound/vibration, and database persistence.
- Do not call a feature complete merely because compilation succeeds. Do not publish a final APK until the relevant runtime checks pass.

## Deferred scope
- Any new idea recalled later by the user is recorded as a separate future development, not silently added to Architecture 1 or 2.