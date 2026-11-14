# Pass 5: Service Decomposition, Controller Discovery Seam, and Preset Profiles

Status: resolved

## Problem Statement

Following Passes 1 through 4, which extracted `AmbientStreamingEngine`, `WledControllerClient`, `ScreenColorProcessor`, and `FixtureGeometryEngine`, several key components still present maintainability and usability bottlenecks:
1. `AmbientCaptureService.kt` is a 1,700-line monolith riddled with double-spacing formatting artifacts, conflating hardware capture (`MediaProjection`, `VirtualDisplay`, `ImageReader`), floating HUD overlay management (`WindowManager`, `SamplingOverlayView`), screen display/power event handling (`DisplayManager`, `PowerManager`), and foreground service orchestration.
2. Device discovery (`WledDiscovery.kt`) remains an unseamed utility in the UI package, binding directly to Android's `NsdManager`. It lacks JVM testability and operates outside `WledControllerClient`.
3. Optical calibration lacks preset profiles. Users must balance multiple fine-grained sliders across screens rather than choosing cohesive viewing moods (Cinema, Gaming, Vivid, Eye Care, Standard).
4. Persisted configuration in `ConfigRepository` lacks atomic export/import and versioned schema migration, risking config corruption or loss across updates.

## Solution

1. **Service Decomposition**:
   - Extract `ScreenCaptureSession` (MediaProjection, VirtualDisplay, ImageReader, HandlerThread, frame dispatch).
   - Extract `SamplingOverlayManager` (WindowManager, floating HUD views, toggle intents, HUD data updates).
   - Extract `DisplayPowerMonitor` (DisplayListener, PowerManager interactive state, blackout triggers, wake restoration).
   - De-duplicate and reformat `AmbientCaptureService` to a ~150-line thin foreground service adapter.

2. **Unified Discovery Seam**:
   - Add `fun discoverDevices(): Flow<DiscoveredWled>` to `WledControllerClient`.
   - Implement `WledDiscoveryProvider` interface (`NsdDiscoveryProvider` for Android OS, controllable flow in `FakeWledControllerClient`).
   - Migrate `DeviceManagerActivity` to consume discovery through `WledControllerClient`.

3. **Preset Profiles**:
   - Define `PresetProfile` in `com.wled.tv.model` with factory presets: `DEFAULT` (Standard), `CINEMA`, `GAMING`, `VIVID`, `EYE_CARE`, and `CUSTOM`.
   - Update `WledConfig` to track `activeProfileId`.
   - Add quick D-pad preset switcher pill on `MainActivity` and full controls with "Reset to [Preset]" in `CalibrationActivity`.
   - Auto-transition to `CUSTOM` upon manual slider edits.

4. **Config Portability & Schema Migration**:
   - Introduce `ConfigSchemaMigrator` in `ConfigRepository` with sequential version migration (`v0` legacy -> `v1` current).
   - Add atomic JSON export/import to `/sdcard/Download/wled_tv_backup.json` and in-app copy/paste dialog.
   - Enforce `"app": "wled-tv"` signature and supported version validation.

## Tickets

1. `01-preset-profile-domain-and-schema-migration.md`: Implement `PresetProfile`, update `WledConfig`, and build `ConfigSchemaMigrator` in `ConfigRepository`.
2. `02-wled-controller-client-discovery-seam.md`: Add `discoverDevices()` to `WledControllerClient`, implement `NsdDiscoveryProvider` and test doubles.
3. `03-ambient-capture-service-decomposition.md`: Extract `ScreenCaptureSession`, `SamplingOverlayManager`, and `DisplayPowerMonitor` from `AmbientCaptureService`.
4. `04-ui-integration-preset-pill-and-backup.md`: Add preset selector on `MainActivity` & `CalibrationActivity`, add backup export/import UI, and migrate `DeviceManagerActivity` discovery.
5. `05-verification-and-release-prep.md`: Run JVM test suite, verify clean build, and bump version to 1.3.6.
