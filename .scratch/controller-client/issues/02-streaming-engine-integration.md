# 02: Connect AmbientStreamingEngine & Service Adapter to WledControllerClient

**What to build:** Refactor `AmbientStreamingEngine` and `AmbientCaptureService` to consume the unified `WledControllerClient` instead of taking separate `WledUdpSender` and `WledHttpClient` instances, delegating 60 FPS frame transmission and fleet blackout cleanly to the client.

**Blocked by:** 01 (Unified WledControllerClient Interface, Default Implementation & Test Double)

**Status:** ready-for-agent

- [ ] `AmbientStreamingEngine` constructor accepts `controllerClient: WledControllerClient = DefaultWledControllerClient.getInstance()` and eliminates separate `udpSender` / `httpClient` parameters.
- [ ] `tickStreaming` invokes `controllerClient.sendFrame(device, smoothBuf, leds)`.
- [ ] `blackout` delegates directly to `controllerClient.blackoutFleet(config.enabledDevices, sendPowerOff)`.
- [ ] `close` delegates to `controllerClient.close()`.
- [ ] `AmbientCaptureService` uses `DefaultWledControllerClient.getInstance()` instead of instantiating `fallbackHttpClient = WledHttpClient()`.
- [ ] `AmbientStreamingEngineTest` and `AmbientCaptureServiceAdapterTest` pass using `FakeWledControllerClient`.
