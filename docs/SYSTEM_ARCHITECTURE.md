# System architecture

## Android client
Jetpack Compose UI, domain state reducer and explainable cluster scoring. The client is designed to remain usable without the remote assistant.

## Domain
Driver commands are validated by a single reducer. UI and future remote commands must use the same transitions. Restaurant ranking is a heuristic that combines distance, restaurant density, observed order counts, observation freshness, public reputation and estimated fuel cost.

## Planned local data
Room database for restaurant identity, address, coordinates, neighbourhood/city, observed order count, last observation time and deduplication key. Customer identity and phone/address details are excluded.

## Planned services
Foreground location service, map/routing provider adapter, share-intent parser, optional overlay, authenticated remote-control API and telemetry queue.

## Trust boundaries
Remote commands require authentication, authorization, expiry/idempotency checks and local validation. Sensitive actions require explicit user confirmation. The app must fail safely when connectivity is unavailable.
