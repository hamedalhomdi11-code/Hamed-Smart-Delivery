# Hamed Smart Delivery — Live GPS Architecture 3

## Accepted behavior
- Request Android fine/coarse location permissions when the app starts if they have not been granted.
- Start a foreground location service after permission is granted; show the ongoing service notification.
- Request GPS and network location providers when available. Do not persist raw location coordinates.
- Render one arrow marker at the user's actual latitude/longitude. Update its geographic position on each location fix and rotate it only when a reliable bearing is available.
- The arrow is part of the map, not a fixed Compose overlay at screen center. Panning the map must move the arrow with the map content.
- Keep a single tracking mode plus stopped state. Do not expose an independent navigation mode, turn-by-turn prompts, or a car marker.
- Preserve the existing Riyadh places database and separate order-origin store. Do not change the Hamed Maps server or production database.

## Implementation notes
- LocationTrackingService owns the foreground GPS session and publishes tracking state/location broadcasts.
- MainActivity requests runtime permissions and keeps UI tracking state synchronized with the service.
- SmartMap subscribes to live GPS/network updates, updates the geographic arrow marker, and uses the same fixes for cluster arrival timing.
- OSRM output is a neutral route preview only. Traffic colors are not fabricated.

## Verification required
- CI compile, unit tests, APK signature verification and emulator smoke tests.
- On a physical Android device: permission prompt, marker movement while walking/driving, bearing rotation, manual map pan, app background/screen off, service notification, and battery usage.
- Physical-device behavior must not be marked complete based only on CI.
