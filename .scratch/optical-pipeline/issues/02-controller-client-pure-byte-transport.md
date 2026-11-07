# 02: WledControllerClient and WledUdpSender Pure Byte Transport

**What to build:** Strip all optical color math and channel order swapping from `WledUdpSender` and `WledControllerClient`, transforming them into pure byte transports where `sendDdpFrame` and `sendDrgbFrame` use chunked `System.arraycopy` memory transfers directly into packet buffers, and multi-device routines respect fixture channel widths.

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

- [ ] `WledControllerClient.sendFrame` and `WledUdpSender.sendDeviceFrame` accept pre-formatted `wirePayload: ByteArray` without performing color reordering or RGBW extraction.
- [ ] `WledUdpSender.sendDdpFrame` removes per-pixel permutation loops and RGBW calculation, setting DDP header type (`0x01` RGB vs `0x02` RGBW) and copying packet chunks directly via `System.arraycopy`.
- [ ] `WledUdpSender.sendDrgbFrame` removes per-pixel permutation loops and copies payload directly via `System.arraycopy`.
- [ ] `WledUdpSender.sendWarlsFrame` accepts pre-ordered wire channels and writes WARLS index prefixes without reordering colors.
- [ ] `DefaultWledControllerClient.flashDevice` and `blackoutFleet` format payloads according to `device.calibration.isRgbw` (4 bytes per LED with white at 255 for RGBW flash, 3 bytes for RGB; all zeros for blackouts).
- [ ] `FakeWledControllerClient` is updated to record exact wire payloads.
- [ ] Unit tests in `WledUdpSenderTest` and `DefaultWledControllerClientTest` verify pure byte transport, DDP chunking with RGB and RGBW payloads, and channel-width aware flash/blackout routines.
