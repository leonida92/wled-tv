# Optical Color Pipeline Consolidation Specification

Status: resolved

## Problem Statement

Currently, optical color processing and physical LED wire encoding are improperly coupled and fragmented across architectural boundaries. 

While `ScreenColorProcessor` performs spatial box sampling and LUT calibration, temporal exponential moving average (EMA) smoothing is delegated to a separate public class (`ColorSmoothingFilter`). More critically, optical color math — channel permutation (converting canonical RGB into GRB, BGR, BRG, RBG, or GBR) and SK6812 4-channel RGBW gamut mapping (`AUTO_SUBTRACT` / `AUTO_ADD`) — is executed inside `WledUdpSender` and `WledControllerClient`.

This boundary violation causes several major defects:
1. **Coupled Network Transport**: The network serialization layer is burdened with optical color logic, duplicating channel-swapping switch statements across DDP, DRGB, and WARLS protocols and forcing per-pixel inspection loops on the 60 FPS hot path instead of high-throughput block memory transfers.
2. **Preview Distortion Risk**: Because wire-channel formatting occurs inside the UDP socket sender, UI visualizers, telemetry HUD snapshots, and live preview listeners are at risk of receiving scrambled or 4-channel buffers if transformation order is shifted.
3. **Hardware Mismatch in Test Modes**: Diagnostic routines in `MainActivity` (rainbow HSV test) and `CalibrationActivity` (real-time color balance preview) send raw RGB buffers directly to `controllerClient.sendFrame`, which previously relied on hidden network sender transformations, preventing test modes from being decoupled from the network client.
4. **Buffer Architecture Ambiguity**: The streaming engine lacks an explicit three-stage memory model for handling 3-channel RGB canonical colors alongside 4-channel RGBW wire payloads, risking garbage-collection allocation churn in the 60 FPS loop.

## Solution

Deepen `ScreenColorProcessor` into the single consolidated optical color processing engine, absorb temporal smoothing, and strip all optical color science from `WledControllerClient` and `WledUdpSender`:

1. **Consolidated Optical Engine (`ScreenColorProcessor`)**:
   - Absorb `ColorSmoothingFilter` as an internal implementation detail of `ScreenColorProcessor`. Callers interact exclusively with `ScreenColorProcessor`, which encapsulates per-device temporal smoothing state, adaptive dual-speed scene-cut detection, and lifecycle resets.
   - Add `encodeWireFrame` to `ScreenColorProcessor` to handle all channel order swapping (RGB, GRB, BGR, BRG, RBG, GBR) and SK6812 RGBW 4-channel gamut mapping (`AUTO_SUBTRACT`, `AUTO_ADD`, `OFF`), writing directly into a pre-allocated destination buffer with zero GC allocations.
2. **Three-Stage Zero-Allocation Pipeline in `AmbientStreamingEngine`**:
   - **Stage 1 (Video Frame Acquisition)**: Spatial box sampling into pre-allocated `rawTargetBuffers` (`leds * 3`) upon new video frame arrival.
   - **Stage 2 (Cadence Smoothing)**: Adaptive dual-speed temporal EMA smoothing into pre-allocated `deviceBuffers` (`leds * 3`) on the steady 60Hz ticker. These canonical RGB buffers feed `liveFrameListener` and `getOverlayData()` for distortion-free UI previews.
   - **Stage 3 (Hardware Wire Encoding)**: Wire channel encoding via `colorProcessor.encodeWireFrame` into pre-allocated `wireBuffers` (`leds * (if (isRgbw) 4 else 3)`) immediately before UDP transmission.
3. **Pure Byte Transport (`WledControllerClient` / `WledUdpSender`)**:
   - Update `sendFrame` to accept pre-formatted `wirePayload: ByteArray`.
   - Remove all color order switch statements, `isRgbw` branches, and RGBW math from `WledUdpSender`.
   - Replace per-pixel transmission loops in `sendDdpFrame` and `sendDrgbFrame` with chunked `System.arraycopy` memory transfers directly into UDP packet buffers.
   - Adapt `flashDevice` and `blackoutFleet` to format payloads using the device's wire channel width (4 bytes for RGBW, 3 bytes for RGB).
4. **UI Test Mode Alignment**:
   - Update `MainActivity` (rainbow HSV loop) and `CalibrationActivity` (preview frame) to invoke `ScreenColorProcessor.encodeWireFrame` before calling `controllerClient.sendFrame`.

## User Stories

1. As an Android TV viewer, I want ambient lighting to track on-screen colors with smooth temporal transitions and instant scene-cut snaps, so that bias lighting feels natural, responsive, and immersive.
2. As an Android TV viewer with SK6812 RGBW light strips, I want pure white and neutral highlights to use the dedicated white diode automatically, so that colors are rich, vibrant, and fixtures do not wash out.
3. As an Android TV viewer with GRB or BGR light strips, I want channel ordering to be mapped accurately without reducing 60 FPS streaming throughput, so that my LEDs display true colors without latency.
4. As an Android TV viewer, I want the TV live preview HUD to display true on-screen colors even when my physical light strip is wired as GRB or RGBW, so that on-screen diagnostics look natural and undistorted.
5. As an Android TV viewer, I want the streaming engine to run with zero garbage collection allocations in the 60 FPS loop, so that long viewing sessions never experience micro-stutters or GC frame drops.
6. As a user adjusting color calibration in `CalibrationActivity`, I want color balance test sliders to reflect real-time changes accurately on physical GRB and RGBW fixtures, so that I can calibrate white balance without hardware color mismatches.
7. As a user testing fixture connectivity in `MainActivity`, I want the rainbow test mode to display accurate colors on my physical LEDs, so that I can verify full-spectrum fixture capability.
8. As a developer, I want `ScreenColorProcessor` to be the sole owner of all optical math (spatial sampling, LUT gamma, temporal smoothing, RGBW extraction, channel ordering), so that color logic is cohesive and centralized.
9. As a developer, I want `ColorSmoothingFilter` to be encapsulated inside `ScreenColorProcessor`, so that external callers don't manage smoothing filter maps or lifecycles.
10. As a developer, I want `ScreenColorProcessor.encodeWireFrame` to write directly into pre-allocated destination buffers, so that channel reordering and RGBW mapping generate zero temporary objects.
11. As a developer, I want `AmbientStreamingEngine` to maintain a distinct canonical RGB buffer for live previews and a distinct wire buffer for UDP transmission, so that telemetry visualizers never receive scrambled wire bytes.
12. As a developer, I want `WledControllerClient` and `WledUdpSender` to be pure byte transports without knowledge of color orders or RGBW modes, so that network protocols are decoupled from optical color science.
13. As a developer, I want DDP and DRGB streaming to use `System.arraycopy` rather than per-pixel switch loops, so that packet serialization achieves sub-millisecond execution on Android TV CPUs.
14. As a developer, I want `DefaultWledControllerClient.flashDevice` to format full-channel white flashes for both 3-channel RGB and 4-channel RGBW fixtures, so that physical fixture identification pulses brightly on all hardware types.
15. As a developer, I want `DefaultWledControllerClient.blackoutFleet` to send zeroed payloads sized to the fixture's channel width, so that RGBW strips turn off completely.
16. As a developer, I want all optical color transformations to be testable with fast, deterministic JVM unit tests without needing UDP sockets or mock network stacks.
17. As a developer, I want `FakeWledControllerClient` to record exact wire payload bytes, so that engine tests can assert both canonical preview RGB and wire channel ordering.

## Implementation Decisions

1. **Encapsulate `ColorSmoothingFilter` in `ScreenColorProcessor`**:
   - Absorb `ColorSmoothingFilter` as an internal/private helper within `com.wled.tv.processing`.
   - `ScreenColorProcessor.reset()` clears all internal smoothing filters along with letterbox/pillarbox state.
   - External consumers only interact with `ScreenColorProcessor.applySmoothing`.

2. **Wire Encoding API in `ScreenColorProcessor`**:
   - Provide a domain method operating directly on `WledDevice`:
     `fun encodeWireFrame(device: WledDevice, sourceRgb: ByteArray, outputWire: ByteArray): Int`
   - Provide a companion/primitive overload for zero-dependency callers:
     `fun encodeWireFrame(sourceRgb: ByteArray, ledCount: Int, colorOrder: String, isRgbw: Boolean, rgbwMode: String, outputWire: ByteArray): Int`
   - Map color order strings to integer constants (`ORDER_RGB = 0`, `ORDER_GRB = 1`, `ORDER_BGR = 2`, `ORDER_BRG = 3`, `ORDER_RBG = 4`, `ORDER_GBR = 5`) for zero-allocation per-frame decoding.
   - For RGBW devices (`isRgbw == true`):
     - Calculate white extraction: `w = min(r, min(g, b))` if mode is `AUTO_SUBTRACT` or `AUTO_ADD`, else `0`.
     - Subtract white if mode is `AUTO_SUBTRACT`: `r' = r - w`, `g' = g - w`, `b' = b - w`.
     - Write 4 bytes per LED: reordered color channels followed by white channel.
   - For RGB devices (`isRgbw == false`):
     - Write 3 bytes per LED with channels reordered according to the parsed color order.
   - Return total bytes written (`ledCount * (if (isRgbw) 4 else 3)`).

3. **Three-Stage Decoupled Pipeline in `AmbientStreamingEngine`**:
   - Introduce `internal val wireBuffers = HashMap<String, ByteArray>()` alongside existing `rawTargetBuffers` and `deviceBuffers`.
   - In `updateConfig(newConfig)`:
     - Retain active device IDs and resize buffers when LED count or RGBW mode changes:
       `wireReqSize = dev.totalLeds * (if (dev.calibration.isRgbw) 4 else 3)`.
   - In `tickStreaming`:
     1. Apply temporal smoothing: `colorProcessor.applySmoothing(dev.id, rawBuf, leds, smoothingFactor, smoothBuf)`.
     2. Wire encode: `colorProcessor.encodeWireFrame(dev, smoothBuf, wireBuf)`.
     3. UDP transmission: `controllerClient.sendFrame(dev, wireBuf, leds)`.
     4. Preview emission: `liveFrameListener?.onDeviceFrameProcessed(dev.id, previewCopy, leds)` continues passing canonical RGB `smoothBuf.copyOf(...)`.
   - In `getOverlayData(dev)`: continues using canonical RGB `smoothBuf` for distortion-free wireframe rendering.

4. **Pure Byte Transport in `WledControllerClient` & `WledUdpSender`**:
   - Update `WledControllerClient.sendFrame`:
     `fun sendFrame(device: WledDevice, wirePayload: ByteArray, ledCount: Int = device.totalLeds): Int`
   - Update `WledUdpSender.sendDeviceFrame`:
     `open fun sendDeviceFrame(device: WledDevice, wirePayload: ByteArray, ledCount: Int = device.totalLeds, timeoutSeconds: Byte = 2): Int`
   - In `WledUdpSender.sendDdpFrame`:
     - Parameters: `(ip: String, port: Int, wirePayload: ByteArray, ledCount: Int, isRgbw: Boolean)`.
     - Remove all color order parsing and RGBW calculation loops.
     - Set DDP header type byte: `if (isRgbw) DDP_TYPE_RGBW else DDP_TYPE_RGB`.
     - For each chunk: copy bytes directly via `System.arraycopy(wirePayload, offset, packetBuffer, DDP_HEADER_LEN, chunkChannels)`.
   - In `WledUdpSender.sendDrgbFrame`:
     - Parameters: `(ip: String, port: Int, timeoutSeconds: Byte, wirePayload: ByteArray, ledCount: Int)`.
     - Remove color order loops; copy bytes directly via `System.arraycopy(wirePayload, 0, packetBuffer, 2, chunkChannels)`.
   - In `WledUdpSender.sendWarlsFrame`:
     - Parameters: `(ip: String, port: Int, timeoutSeconds: Byte, wirePayload: ByteArray, ledCount: Int)`.
     - Interleave index byte with pre-ordered 3-channel bytes from `wirePayload`.
   - In `DefaultWledControllerClient.flashDevice`:
     - Size `whiteFrame` and `blackFrame` to `leds * (if (device.calibration.isRgbw) 4 else 3)`. Fill white with `255.toByte()`.
   - In `DefaultWledControllerClient.blackoutFleet`:
     - Size `blackBuf` to `leds * (if (device.calibration.isRgbw) 4 else 3)`.

5. **UI Test Mode Migration**:
   - In `MainActivity`: pre-allocate or allocate `wireBuffer` for rainbow test, run `ScreenColorProcessor.encodeWireFrame(dev, testBuffer, wireBuffer)`, and send `wireBuffer`. Keep UI preview `tvPreviewView.updateDeviceLiveColors` on canonical `testBuffer`.
   - In `CalibrationActivity`: run `ScreenColorProcessor.encodeWireFrame(dev, frame, wireBuffer)` before calling `sendFrame`.

## Testing Decisions

- **Fast JVM Unit Loop**: All unit tests must execute in ~8 seconds via `cmd /c "gradlew.bat testDebugUnitTest"` without Android emulators.
- **Seams Under Test**:
  1. **Primary Seam (`ScreenColorProcessor`)**:
     - Unit test suite `ScreenColorProcessorTest`:
       - Absorb existing `ColorSmoothingFilterTest` scenarios (instant factor, subtle smoothing, scene-cut instant snap, reset).
       - New tests for `encodeWireFrame`: verify all 6 color orders (`RGB`, `GRB`, `BGR`, `BRG`, `RBG`, `GBR`) with exact channel assertions.
       - New tests for `encodeWireFrame`: verify SK6812 RGBW modes (`AUTO_SUBTRACT`, `AUTO_ADD`, `OFF`) with 4-channel byte assertions.
       - Verification of zero-allocation safety on repeated calls.
  2. **Integration Seam (`AmbientStreamingEngine`)**:
     - Unit test suite `AmbientStreamingEngineTest`:
       - Verify three-stage pipeline: raw target buffer -> canonical smoothed buffer -> wire-encoded buffer.
       - Verify that `liveFrameListener` and `getOverlayData()` receive canonical RGB bytes without GRB scrambling or 4-channel expansion.
       - Verify that `FakeWledControllerClient.sentFrames` receives the exact wire-encoded payload bytes.
  3. **Transport Seam (`WledUdpSender` & `DefaultWledControllerClient`)**:
     - Unit test suite `WledUdpSenderTest`:
       - Updated to verify pure byte transport (DDP chunking, multi-packet push flag, DRGB MTU clamping, WARLS indexing) using pre-formatted wire payloads.
       - Verify header type flag `0x01` (RGB) vs `0x02` (RGBW) in DDP frames.
     - Unit test suite `DefaultWledControllerClientTest`:
       - Verify delegation to UDP sender and HTTP client.
       - Verify `flashDevice` and `blackoutFleet` payload sizing for RGB vs RGBW devices.
  4. **UI Seam (`UiColorPipelineTest`)**:
     - Verify that `MainActivity` rainbow test and `CalibrationActivity` balance preview apply wire encoding before transmission.

## Out of Scope

- Modifying WLED controller firmware or underlying UDP network specifications.
- Modifying `ConfigRepository` or reactive state store architecture established in Pass 1.
- Altering the physical layout border calculation logic in `PerimeterConfig`.

## Further Notes

- Full backward compatibility with existing user configurations and saved settings.
- Fully adheres to [CODING_STANDARDS.md](file:///c:/Users/user/Desktop/git/wled-tv/CODING_STANDARDS.md) (zero GC allocations on hot paths, non-blocking UI, main-safe suspend functions).
- References: [GLOSSARY.md](file:///c:/Users/user/Desktop/git/wled-tv/GLOSSARY.md) and [ADR 0003](file:///c:/Users/user/Desktop/git/wled-tv/docs/adr/0003-optical-color-pipeline-and-wire-encoding.md).
