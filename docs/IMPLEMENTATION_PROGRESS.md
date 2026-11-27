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
