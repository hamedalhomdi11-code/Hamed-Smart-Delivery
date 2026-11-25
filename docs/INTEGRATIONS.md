# Integrations and prerequisites

- **Map rendering:** OpenStreetMap tiles are integrated through osmdroid, with visible attribution. Tile usage must comply with the OSM tile usage policy; public tile servers do not guarantee production availability.
- **Routing:** still requires a routing provider, usage-policy review, route drawing, rerouting and real-device testing. Do not treat the current map as turn-by-turn navigation.
- **Location:** Android foreground location permission is integrated for the active app session. A foreground location service and notification are still required for reliable navigation while the app is backgrounded.
- **Remote control:** optional; requires a separately deployed authenticated backend, per-device credentials, command expiry/idempotency, local state-machine validation, audit history and revocation. Do not embed long-lived secrets in the APK.
- **HungerStation links:** Android share/open-with integration can process links the user explicitly shares; private app APIs are not assumed or scraped.
- **Restaurant data:** store only restaurant/business details and delivery observations needed for ranking. Never retain customer names, phone numbers or delivery addresses.
- **Release signing:** configure a protected signing key outside source control before publishing a release APK. A CI debug APK is not a final signed production release.
