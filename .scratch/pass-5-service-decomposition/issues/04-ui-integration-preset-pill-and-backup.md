# Issue 04: UI Integration (Preset Pill, Backup, and Discovery)

Status: resolved

## Description

1. `MainActivity`:
   - Add a quick Preset Pill (similar to Throughput Pill) opening a remote D-pad dialog to switch between `DEFAULT`, `CINEMA`, `GAMING`, `VIVID`, `EYE_CARE`.
2. `CalibrationActivity`:
   - Add preset spinner/row with factory presets and `CUSTOM`.
   - Add "Reset to [Preset]" action when sliders differ from the active preset.
   - When any slider is modified, auto-transition active profile to `CUSTOM`.
3. `SystemSettingsActivity`:
   - Add "Backup & Restore" section:
     - "Export Configuration" (writes `wled_tv_backup.json` to Downloads folder and displays JSON copy dialog).
     - "Import Configuration" (reads file from Downloads folder or opens paste JSON dialog).
4. `DeviceManagerActivity`:
   - Replace raw `WledDiscovery` instantiation with `controllerClient.discoverDevices()`.
5. UI tests in `UiMigrationTest.kt` verifying state updates and button wiring.

## Answer

Issue 04 has been completely implemented and verified with JVM unit tests:

1. **MainActivity Preset Pill & Dialog**:
   - Added `btnPresetPill` and `tvPresetText` to `activity_main.xml` in the top header adjacent to `btnThroughputPill`.
   - `MainActivity.kt` binds `btnPresetPill` and observes `configRepo.configFlow`, keeping `tvPresetText` synced with `config.activeProfile.name` ("Profile: Standard", "Profile: Cinema", etc.).
   - Clicking `btnPresetPill` opens a remote-friendly `AlertDialog` listing factory presets (Standard, Cinema, Gaming, Vivid, Eye Care). Selecting a preset applies it via `configRepo.updateConfig { it.applyPreset(preset) }`.

2. **CalibrationActivity Preset Controls & Custom Transition**:
   - Added `layoutPresetRow`, `btnPresetSelector`, `btnResetPreset`, and `tvPresetDescription` to `activity_calibration.xml`.
   - `btnPresetSelector` displays the current active profile (or "Custom") and opens an `AlertDialog` listing all presets (including Custom).
   - Any modification to calibration sliders (saturation, contrast, smoothing, gamma, gain, black threshold, color order) through `updateTargetCalibration` automatically transitions `activeProfileId` to `PresetProfile.CUSTOM.id`.
   - Added `btnResetPreset` ("Reset to [Preset]"), which enables when the current values deviate from the baseline factory preset or when in `CUSTOM` mode. Clicking it restores the factory preset calibration values and resets `activeProfileId`.

3. **SystemSettingsActivity Backup & Restore Section**:
   - Added "Export Configuration" (`itemExportConfig`, `tvExportConfigAction`) and "Import Configuration" (`itemImportConfig`, `tvImportConfigAction`) cards to `activity_system_settings.xml`.
   - "Export Configuration" serializes the current setup with `configRepo.exportConfig()`, saves to `wled_tv_backup.json` in `Environment.DIRECTORY_DOWNLOADS` (falling back gracefully to `context.getExternalFilesDir(null)`), and displays a dialog containing the JSON, file path, and a "Copy JSON" button.
   - "Import Configuration" detects existing `wled_tv_backup.json` files in Downloads, provides an `AlertDialog` permitting restoring from file or opening a paste JSON dialog with an `EditText`, and applies imported settings through `configRepo.importConfig(json)`.

4. **DeviceManagerActivity Discovery Seam Migration**:
   - Replaced raw `WledDiscovery(this)` instantiation in `discoverWledDevices()` with `controllerClient.discoverDevices()`.
   - Removed obsolete `WledDiscovery` import, ensuring `DeviceManagerActivity` relies solely on `WledControllerClient`.

5. **Unit & UI Verification**:
   - Created `app/src/test/java/com/wled/tv/ui/UiPresetAndBackupTest.kt` verifying:
     - Preset application and reactive updates to `activeProfileId` across all built-in profiles.
     - Slider modifications transitioning state to `PresetProfile.CUSTOM`.
     - "Reset to Preset" restoring pristine factory values.
     - `DeviceManagerActivity` using `controllerClient.discoverDevices()` with `FakeWledControllerClient`.
     - Round-trip config export, signature validation (`"app": "wled-tv"`, `"schemaVersion": 1`), and corrupt JSON rejection.
     - Layout XML declarations for all required preset, pill, and backup view IDs.
   - 100% of JVM unit tests pass via `gradlew.bat testDebugUnitTest`.
