# 04: UI Test Pattern and Calibration Preview Migration

**What to build:** Migrate `MainActivity` (rainbow HSV test loop) and `CalibrationActivity` (real-time color balance preview) to wire-encode frames through `ScreenColorProcessor.encodeWireFrame` before calling `controllerClient.sendFrame`, ensuring physical LEDs display true colors on GRB, BGR, and RGBW strips without leaking color science back into the network client.

**Blocked by:** 01 (ScreenColorProcessor Wire Encoding and Smoothing Absorption), 02 (WledControllerClient and WledUdpSender Pure Byte Transport)

**Status:** ready-for-agent

- [ ] `MainActivity` test pattern routine allocates or pre-allocates a wire buffer, runs `ScreenColorProcessor.encodeWireFrame(dev, testBuffer, wireBuffer)`, and sends `wireBuffer` via `controllerClient.sendFrame`.
- [ ] `MainActivity` UI preview (`tvPreviewView.updateDeviceLiveColors`) continues receiving canonical `testBuffer` for accurate on-screen display.
- [ ] `CalibrationActivity` balance preview routine runs `ScreenColorProcessor.encodeWireFrame(dev, frame, wireBuffer)` before calling `controllerClient.sendFrame`.
- [ ] Unit tests in `UiColorPipelineTest` (or updated `UiMigrationTest`) verify that diagnostic test loops correctly invoke `encodeWireFrame` and transmit wire-formatted bytes to `FakeWledControllerClient`.
