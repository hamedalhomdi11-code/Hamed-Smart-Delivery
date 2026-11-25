# Integrations and prerequisites

- **Map renderer:** MapLibre Native Android OpenGL SDK. The app loads the OpenFreeMap Liberty style and enables map attribution. Review the style/tiles provider's usage terms before commercial production.
- **Routing:** a best-effort driving route preview uses the public OSRM demo endpoint. It is not guaranteed for production traffic, does not provide voice instructions in this build, and may be rate-limited. Production requires a supported routing provider or a managed self-hosted OSRM/Valhalla instance.
- **Location:** Android foreground location permission and a user-started location foreground service are implemented. The service displays an ongoing notification and does not persist raw coordinates.
- **Shared destinations:** common coordinate pairs and full Google Maps coordinate links can be extracted from Android shares. Short links and address-only text still need secure resolution/geocoding.
- **Remote control:** optional; requires a separately deployed authenticated backend, per-device credentials, command expiry/idempotency, local state-machine validation, audit history and revocation. Never embed long-lived secrets in the APK.
- **HungerStation:** Android share/open-with integration processes links explicitly shared by the user; private app APIs are not assumed or scraped.
- **Restaurant data:** store only restaurant/business details and delivery observations needed for ranking. Never retain customer names, phone numbers or delivery addresses.
- **Release signing:** configure a protected signing key outside source control before publishing a release APK. A CI debug APK is installable for testing but is not a production-signed release.
