# Hamed Smart Delivery — Implementation Progress

Date: 2026-11-27
Target release: **0.4.3** (versionCode 7)
Working branch: `feature/0.4.3-final-integrated-gps-20261128`
Base branch: `feature/riyadh-local-db-integration-20261126`
Base implementation: Architecture 1/2 branch PR #10; this integrated branch retains the original database and adds enabled foreground GPS.

## Release baseline

- Release 0.4.3 is based on the existing 0.4.2 codebase; the project is not being rewritten.
- The source version has been advanced to versionName 0.4.3 / versionCode 7.
- CI artifact naming has been advanced to 0.4.3.
- The consolidated requirements are recorded in `docs/ARCHITECTURE_CORRECTIONS_AR.md` (corrections 001–006).
- These version-label changes do not mean the functional corrections are complete.

## Implementation status

- 001–006: requirements consolidated and verified in the correction log.
- Cluster workflow, 5-minute arrival timer, 10-minute manual exclusion, 15-minute automatic exclusion, cluster history, and order-origin persistence are present in code; CI and runtime validation are still required.
- GPS permission request and foreground GPS service are enabled; live arrow marker is anchored to geographic coordinates.
- App-state and voice announcement persistence are present; full device behavior remains to be verified.
- No Riyadh database, schema, or server changes were made.
- No merge to the default branch was performed.

## Safety and scope

- GPS live tracking is explicitly enabled by the user's latest instruction. No separate turn-by-turn navigation mode is included.
- Route preview is not a navigation instruction system. No fabricated live-traffic coloring is allowed.
- Audio must not prompt interaction with the phone while driving. Any safe, non-driving status announcements must be event-accurate, non-overlapping, and user-controllable.
- Persisted data must not present stale values as current.
- Preserve the existing project structure and working functionality; make focused changes only.

## Verification gates

- Run unit tests and assemble the 0.4.3 Debug APK.
- Verify the APK exists, is non-empty, and passes signature verification.
- Run emulator smoke tests where CI supports them.
- A Debug APK may not install as an update over an installed 0.4.2 build if signing certificates differ.
- Do not claim physical-device validation unless it has actually been performed.
- Do not claim a release-signed update APK unless the original 0.4.2 signing key is available and its certificate fingerprint matches.

## Next implementation sequence

1. Run CI and resolve any Kotlin/Android compile or emulator failures.
2. Verify permission prompts, live geographic marker movement, and foreground service behavior on a physical device.
3. Verify timer transitions, exclusions, task pause/resume, audio/vibration preferences, and process restoration.
4. Verify database integrity and APK signature; do not claim production signing unless the original keystore matches.

## Recovery audit and fixes — 2026-11-28

The installed 0.4.3 report identified two regressions: GPS start/stop state was being changed optimistically by the UI, and cluster discovery could remain empty when no cluster center fell within the fixed 5 km radius.

Focused fixes applied on the existing feature branch:

- GPS stop now sends the service's explicit `ACTION_STOP` command. UI state is changed only when the foreground service broadcasts its actual state.
- The location service removes any previous provider subscriptions before starting a new session and explicitly removes foreground notification state when stopped.
- Cluster discovery expands through 1, 3, 5, 8, 12 and 15 km. If no eligible cluster exists within 15 km, it tries the nearest real database clusters only while the GPS coordinate is inside the Riyadh database coverage bounds.
- Discovery callbacks report whether a usable cluster was selected. If nearby clusters are temporarily excluded or blocked by the four-other-clusters repeat rule, the search continues to wider radii rather than treating the first batch as final.
- The repeat-visit rule no longer falls back to a recently visited cluster merely because it is the first available result. An order-producing cluster remains eligible under the accepted exception.
- The bundled Riyadh asset version was bumped so an app update replaces an older on-device database copy; import validation now checks for non-empty restaurant/cafe clusters, not just a generic v3 row.

Verification status for this recovery patch:

- Render Docker build and unit tests: pending final deploy result.
- APK contents/signature/HTTP download: pending final build.
- Physical-device GPS start/stop, map route drawing, background behavior and battery checks: still require testing on the user's phone.
- No merge to the default branch, no change to the Hamed Maps server, and no change to the source Riyadh database schema.
- Map discovery and route jobs now read changing visit/exclusion and display preferences without tearing down the live location listener; if a route request is canceled during lifecycle cleanup, its pending flag is reset so the next GPS fix can retry.
- The Render image build now refuses to publish unless the 0.4.3 APK exists, contains the bundled Riyadh SQLite asset, reports versionName 0.4.3, and passes apksigner verification; it publishes APK SHA-256 and certificate details for inspection.
- The geographic arrow now has a lightweight pulse/glow animation (paused during position interpolation to avoid icon-update contention); route previews use a neutral line color rather than implying live traffic conditions.
- Cluster retries now use the latest GPS fix rather than reusing the first callback's coordinates; cached location is used only when it is no older than two minutes, preventing a stale last-known position from pinning discovery to the wrong part of the map.
- Android package versionCode is incremented from 7 to 8 while versionName remains 0.4.3, so this recovery APK is a forward update rather than a same-code rebuild.

## Second-stage recovery findings — 2026-11-28

A deeper pass found a concrete cluster-selection defect: `RestaurantDiscoveryClient` truncated every radius batch to the first 30 ranked candidates (and the wide-area fallback to the nearest 30) before the caller could apply exclusion and four-other-clusters repeat rules. If those 30 were ineligible, valid clusters later in the same Riyadh database were never evaluated. The callback now receives every candidate in the radius and the full coverage fallback; the map still renders only a small marker subset.

Route calculation now tries the primary OSRM demo and a secondary FOSSGIS OSRM car endpoint when the first provider times out or cannot return a route. Both are public best-effort demo services, not production SLAs; public usage should remain light and a self-hosted router is the long-term reliable option.

GPS control was hardened:
- The map now exposes the same tracking toggle as Profile, so start/stop is directly accessible without leaving the map.
- The toggle reads the persisted foreground-service state at click time rather than trusting a possibly stale Compose state.
- Start/stop exceptions are surfaced to the user instead of silently appearing as an unresponsive button.
- The foreground service logs explicit start/stop and provider failures, removes GPS updates and the foreground notification on stop, and cleans up when no location provider is enabled.

Verification:
- These changes are committed on the existing feature branch only.
- A fresh Render build against the latest branch head is required; the earlier in-progress deploy used an older commit and does not include these changes.
- Physical-device start/stop, GPS fix, database import and road-route rendering still require confirmation on the user's phone.
- Follow-up hardening: the map itself now exposes the GPS toggle; the toggle reads persisted service state and displays exceptions; GPS service start/stop and provider failures are logged; the no-provider path no longer recreates an orphaned ongoing notification after stopping.
- The duplicate Profile GPS control was removed so the app has one clear start/stop GPS toggle on the map, matching the accepted single-control design; Profile remains for preferences and language.
- Driver search no longer switches to SEARCHING optimistically when GPS is stopped: it waits for the foreground service's active-state broadcast, and reports a clear timeout/error if GPS cannot start. The permission prompt may remain open without prematurely expiring the pending search.
