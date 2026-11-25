# Android build validation

This change line adds MapLibre Native, a user-started foreground location service, shared coordinate parsing, an OSRM route preview, and reducer-backed driver lifecycle controls.

CI must pass both:
- `:app:testDebugUnitTest`
- `:app:assembleDebug`

A successful debug APK build verifies compilation and unit tests only. It does not replace installation, GPS, background execution, map rendering, routing, battery, or release-signing tests on a physical Android device.

The OSRM public demo endpoint is development-only and is not a production availability guarantee.
