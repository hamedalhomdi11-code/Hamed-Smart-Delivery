# Integrations and prerequisites

- **Maps and routing:** choose a provider and configure its API key, billing and usage policy before enabling live maps.
- **Location:** Android runtime permissions and a foreground location service are required for reliable active navigation; background access is subject to Android policy.
- **Remote control:** requires a separately deployed authenticated backend, per-device credentials, command validation, audit history and revocation.
- **HungerStation links:** Android share/open-with integration can process links the user explicitly shares; private app APIs are not assumed.
- **Restaurant data:** store only restaurant/business details and delivery observations needed for ranking. Do not retain customer personal data.
- **Release signing:** configure a protected signing key outside source control before publishing a release APK.
