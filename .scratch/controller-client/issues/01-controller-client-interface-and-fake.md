# 01: Unified WledControllerClient Interface, Default Implementation & Test Double

**What to build:** A unified network controller client interface operating on `WledDevice` domain models that encapsulates synchronous UDP frame streaming (DDP, DRGB, WARLS) and asynchronous HTTP controller state management (power, brightness, reachability, device info, concurrent fleet blackout, and device flashing), along with an application-scoped singleton factory and an in-memory `FakeWledControllerClient` for fast JVM unit testing.

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

- [ ] `WledControllerClient` interface is defined in `com.wled.tv.network` with synchronous `sendFrame(device: WledDevice, rgb: ByteArray, ledCount: Int): Int` and `suspend` methods for `wakeAndSetBrightness`, `turnOff`, `checkConnection`, `fetchDeviceInfo`, `blackoutFleet`, and `flashDevice`.
- [ ] `DefaultWledControllerClient` delegates UDP protocol packing to internal `WledUdpSender` and HTTP calls to internal `WledHttpClient`.
- [ ] `blackoutFleet(devices: List<WledDevice>, sendPowerOff: Boolean)` sends zeroed UDP bursts immediately to all devices, and concurrently dispatches HTTP `turnOff` requests via coroutines with timeout safeguards.
- [ ] `flashDevice(device: WledDevice, durationMs: Long)` sends full-white UDP frame, delays, and sends full-black UDP frame without caller buffer allocation.
- [ ] `WledTvApplication` exposes `controllerClient` and `DefaultWledControllerClient.getInstance()` / `setInstanceForTesting(...)` enables test injection.
- [ ] `FakeWledControllerClient` records sent frames, power states, blackout calls, and supports configurable reachability and device info responses.
- [ ] Unit test `DefaultWledControllerClientTest` passes in pure JVM verification.
