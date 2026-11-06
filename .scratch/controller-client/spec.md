# Unified WledControllerClient Specification

Status: resolved

## Problem Statement

Currently, communication with WLED fixtures is fragmented between two low-level classes: `WledUdpSender` (UDP protocols: DDP, DRGB, WARLS) and `WledHttpClient` (HTTP REST commands: power, brightness, device info). 

Five separate UI activities (`MainActivity`, `CalibrationActivity`, `CornerFinderActivity`, `DeviceManagerActivity`, `EditDeviceActivity`), along with `AmbientStreamingEngine` and `AmbientCaptureService`, each instantiate their own local `DatagramSocket` and `OkHttpClient` instances. Callers are forced to unpack domain models into 9 primitive parameters (`ip`, `port`, `protocol`, `rgb`, `ledCount`, `colorOrder`, `isRgbw`, `rgbwMode`, `timeoutSeconds`), manage manual socket teardown in `onDestroy()`, and duplicate boilerplate loops for device identification flashing and blackout sequences. Furthermore, diagnostic test patterns currently bypass the centralized streaming engine and fight over uncoordinated socket instances.

## Solution

Consolidate all WLED network input and output behind a deep, unified `WledControllerClient` interface operating directly on `WledDevice` domain models:
1. Provide an application-scoped singleton (`DefaultWledControllerClient`) holding a single shared, reusable `DatagramSocket` and connection-pooled `OkHttpClient`.
2. Encapsulate UDP packet formatting (DDP multi-packet, DRGB MTU clamping, WARLS indexing, RGBW channel conversion) and HTTP device control (`/json/state`, `/json/info`) as internal implementation details.
3. Keep the high-frequency UDP streaming hot path (`sendFrame`) synchronous for deterministic 60Hz loop pacing without coroutine dispatch jitter, while modeling asynchronous HTTP and composite operations (`wakeAndSetBrightness`, `turnOff`, `checkConnection`, `fetchDeviceInfo`, `blackoutFleet`, `flashDevice`) as `suspend` functions.
4. Execute multi-device HTTP requests concurrently during `blackoutFleet` so that slow or offline controllers do not delay turning off other fixtures.
5. Provide a canonical in-memory `FakeWledControllerClient` for deterministic JVM unit testing of `AmbientStreamingEngine` and UI activities with zero network I/O.

## User Stories

1. As an Android TV viewer, I want the ambient bias lighting to stream at a rock-solid 60 FPS without frame drops, so that visual immersion is fluid and tear-free.
2. As an Android TV viewer, I want the app to use minimal system resources and zero socket churn, so that background streaming never degrades video playback performance.
3. As an Android TV viewer, I want all light strips to instantly turn off when playback stops or the TV sleeps, so that fixtures do not stay powered on when unused.
4. As an Android TV viewer with multiple WLED fixtures, I want all fixtures to turn off simultaneously without waiting sequentially for unreachable controllers to time out.
5. As a user in Device Manager, I want to tap "Identify/Flash" on a fixture, so that the light strip pulses white and black once without lagging the TV interface.
6. As a user in Edit Device, I want to test connection reachability and auto-fetch LED counts, so that device setup is automated and verified immediately.
7. As a user in Calibration or Corner Finder, I want test patterns to render immediately on my LEDs without background ambient video capture interfering.
8. As a developer, I want all network interactions to accept `WledDevice` domain models rather than 9 unpacked primitive parameters, so that code is clean, type-safe, and self-documenting.
9. As a developer, I want a single unified `WledControllerClient` interface, so that activities never instantiate raw sockets or manage manual socket lifecycles.
10. As a developer, I want `WledControllerClient.getInstance()` and `setInstanceForTesting(...)`, so that UI activities and services can easily obtain the client and swap in test doubles.
11. As a developer, I want `sendFrame` to be a non-suspending, zero-allocation call, so that `AmbientStreamingEngine.tickStreaming` executes without coroutine dispatch overhead.
12. As a developer, I want `blackoutFleet` to encapsulate the 3-frame UDP zero burst and parallel HTTP power-off calls, so that blackout logic is never duplicated across activities or engines.
13. As a developer, I want `flashDevice` to encapsulate the white/black pulse sequence with an internal timer, so that UI activities don't allocate temporary buffers or duplicate delay logic.
14. As a developer, I want a canonical `FakeWledControllerClient` test double, so that all JVM unit tests run in milliseconds without network sockets or flaky timeouts.
15. As a developer, I want `WledUdpSender` and `WledHttpClient` to be `internal` to the network package, preventing accidental bypass of the architectural seam.

## Implementation Decisions

1. **Interface Contract (`WledControllerClient`)**:
   - `fun sendFrame(device: WledDevice, rgb: ByteArray, ledCount: Int = device.totalLeds): Int` (synchronous, returns packet count)
   - `suspend fun wakeAndSetBrightness(device: WledDevice): Boolean`
   - `suspend fun wakeAndSetBrightness(ip: String, brightness: Int = 255): Boolean`
   - `suspend fun turnOff(ip: String): Boolean`
   - `suspend fun checkConnection(ip: String): Boolean`
   - `suspend fun fetchDeviceInfo(ip: String): WledDeviceInfo?`
   - `suspend fun blackoutFleet(devices: List<WledDevice>, sendPowerOff: Boolean = true)`
   - `suspend fun flashDevice(device: WledDevice, durationMs: Long = 500L)`
   - `fun close()`

2. **Application Singleton & Test Seam**:
   - `WledTvApplication` exposes `val controllerClient: WledControllerClient by lazy { DefaultWledControllerClient.getInstance() }`.
   - Companion object on `DefaultWledControllerClient` provides `getInstance(): WledControllerClient` and `setInstanceForTesting(client: WledControllerClient?)`.

3. **Production Implementation (`DefaultWledControllerClient`)**:
   - Holds a single shared `WledUdpSender` (reusable `DatagramSocket`) and `WledHttpClient` (connection-pooled `OkHttpClient`).
   - Delegates protocol packing and socket transmission to internal `WledUdpSender`.
   - Delegates HTTP operations to internal `WledHttpClient`.
   - In `blackoutFleet`, immediately sends zeroed UDP bursts to all devices sequentially (UDP send is non-blocking sub-millisecond), then launches parallel HTTP `turnOff(ip)` requests using `coroutineScope` and `async` with a strict timeout.
   - In `flashDevice`, sends full-white UDP frame, waits `durationMs` via coroutine delay, then sends full-black UDP frame.

4. **Integration with `AmbientStreamingEngine`**:
   - Update `AmbientStreamingEngine` constructor to receive `controllerClient: WledControllerClient = DefaultWledControllerClient.getInstance()`.
   - Replace separate `udpSender` and `httpClient` parameters with `controllerClient`.
   - `tickStreaming` calls `controllerClient.sendFrame(device, smoothBuf, leds)`.
   - `blackout` delegates directly to `controllerClient.blackoutFleet(config.enabledDevices, sendPowerOff)`.
   - `close()` delegates to `controllerClient.close()`.

5. **UI Activity Migration**:
   - Replace `private val udpSender = WledUdpSender()` and `private val httpClient = WledHttpClient()` across `MainActivity`, `CalibrationActivity`, `CornerFinderActivity`, `DeviceManagerActivity`, and `EditDeviceActivity` with `private val controllerClient: WledControllerClient = DefaultWledControllerClient.getInstance()`.
   - Remove manual `udpSender.close()` calls from all `onDestroy()` lifecycle methods (the shared client is application-scoped).
   - Replace duplicate flash routines with `lifecycleScope.launch { controllerClient.flashDevice(device) }`.
   - Replace manual reachability and info checks with `controllerClient.checkConnection(ip)` and `controllerClient.fetchDeviceInfo(ip)`.

6. **Encapsulation**:
   - Mark `WledUdpSender` and `WledHttpClient` as `internal` within package `com.wled.tv.network`.

## Testing Decisions

- **Fast JVM Unit Loop**: All unit tests must execute in ~8 seconds via `cmd /c "gradlew.bat testDebugUnitTest"` without Android emulators.
- **`FakeWledControllerClient`**:
  - Implements `WledControllerClient`.
  - Maintains in-memory logs: `sentFrames: List<SentFrameRecord>`, `powerState: MutableMap<String, Boolean>`, `blackoutCalls: Int`, configurable mock responses for `checkConnection` and `fetchDeviceInfo`.
- **Unit Test Coverage**:
  - `DefaultWledControllerClientTest`: verifies delegation to UDP sender and HTTP client, concurrent blackout execution, and parameter mapping from `WledDevice`.
  - `AmbientStreamingEngineTest`: updated to inject `FakeWledControllerClient` and verify frame streaming and blackout delegation without mocks.
  - `UiControllerClientMigrationTest`: verifies that UI activities correctly invoke `WledControllerClient` for test patterns, flashing, and reachability.

## Out of Scope

- Optical color pipeline refactoring (Candidate 4 / color smoothing and SK6812 RGBW gamut mapping in `ScreenColorProcessor`). That is reserved for Pass 3.
- Modifying WLED hardware firmware protocols or DDP specification.
- Changing `ConfigRepository` or reactive state flow architecture established in Pass 1.

## Further Notes

- Maintains complete backward compatibility with existing user configurations and Android TV settings.
- Follows [CODING_STANDARDS.md](file:///c:/Users/user/Desktop/git/wled-tv/CODING_STANDARDS.md) (non-blocking UI, functional transforms, encapsulated snapshots).
