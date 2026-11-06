# 04: UI Activity & Overlay Migration

**What to build:** Updates to all 8 configuration activities and floating overlay views to obtain configuration through `ConfigRepository`, apply mutations via `updateConfig(...)`, and observe `AmbientCaptureService.activeEngine` for live frame previews and telemetry graphs without static backdoors or memory leaks.

**Blocked by:** 03: Slim AmbientCaptureService Adapter

**Status:** resolved

- [x] `MainActivity` observes `ConfigRepository.configFlow` to render device docks and status indicators.
- [x] `MainActivity` collects `activeEngine` lifecycle-safely to attach live frame preview listeners and update throughput dialogs.
- [x] `SamplingOverlayView` observes `activeEngine` for real-time sampling crops and telemetry text.
- [x] Diagnostic test patterns in `MainActivity`, `CalibrationActivity`, and `CornerFinderActivity` pause ambient capture via `engine.isTestingOverride = true` rather than a global static variable.
- [x] `EditDeviceActivity`, `DeviceManagerActivity`, `CalibrationActivity`, `ZoneEditorActivity`, `CornerFinderActivity`, `SystemSettingsActivity`, and `SettingsActivity` apply mutations through `ConfigRepository.updateConfig(...)`, removing all calls to `currentServiceInstance?.reloadConfig()`.
