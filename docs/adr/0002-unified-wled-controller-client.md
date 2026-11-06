# Unified WledControllerClient Interface and Shared Socket Lifecycle

Consolidated UDP protocol frame transmission (DDP, DRGB, WARLS) and HTTP REST device management (`/json/state`, `/json/info`) behind a unified `WledControllerClient` interface operating on `WledDevice` domain models, backed by an application-scoped singleton with a test-injection seam.

`WledUdpSender` and `WledHttpClient` are encapsulated as `internal` delegates behind `DefaultWledControllerClient`. The UDP streaming hot path (`sendFrame`) remains synchronous for deterministic 60Hz loop pacing without coroutine dispatch jitter, while HTTP operations and fleet-wide actions (`blackoutFleet`, `wakeAndSetBrightness`, `flashDevice`) execute asynchronously with parallel HTTP dispatch across devices.

This eliminates independent `DatagramSocket` churn, ephemeral port exhaustion, and duplicate socket buffers across UI activities, allows UI diagnostic test frames to share the client during ambient capture override, and provides a canonical `FakeWledControllerClient` for fast, zero-I/O JVM testing.
