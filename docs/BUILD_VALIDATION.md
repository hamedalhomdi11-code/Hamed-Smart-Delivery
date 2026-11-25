# Android build validation

This implementation uses MapLibre Native 13.7.1 (Kotlin metadata 2.2), Kotlin Gradle Plugin 2.2.10, AGP 8.10.1 and Gradle 8.11.1.

CI must pass both `:app:testDebugUnitTest` and `:app:assembleDebug`.

A successful debug build verifies compilation and unit tests only. It does not replace physical-device validation for map rendering, GPS, background execution, routing, battery use, or protected release signing.

The public OSRM demo endpoint is development-only and is not a production availability guarantee.
