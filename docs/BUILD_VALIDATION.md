# Android build validation

The current build includes MapLibre Native OpenGL, a user-started foreground location service, shared coordinate parsing, an OSRM route preview, and reducer-backed manual driver lifecycle controls.

CI must pass both `:app:testDebugUnitTest` and `:app:assembleDebug`.

A successful debug build verifies compilation and unit tests only. It does not replace physical-device validation for map rendering, GPS, background execution, routing, battery use, or protected release signing.

The public OSRM demo endpoint is development-only and is not a production availability guarantee.
