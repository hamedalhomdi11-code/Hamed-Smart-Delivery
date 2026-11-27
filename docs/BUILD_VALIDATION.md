# Android build validation

Build toolchain: MapLibre Native 13.7.1, Kotlin Gradle Plugin 2.2.21, AGP 8.10.1, Gradle 8.11.1 and JDK 17.

CI must pass `:app:testDebugUnitTest` and `:app:assembleDebug`.

A successful debug build verifies compilation and unit tests only. Physical-device validation for map rendering, GPS, background execution, routing, battery use and protected release signing remains mandatory.

The public OSRM demo endpoint is development-only and is not a production availability guarantee.
