# Issue 01: Preset Profile Domain and Schema Migration

Status: resolved

## Description

1. Define `PresetProfile` in `com.wled.tv.model`:
   - Factory presets: `DEFAULT` (Standard baseline), `CINEMA`, `GAMING`, `VIVID`, `EYE_CARE`, `CUSTOM`.
   - Preset values map into `ColorCalibration`.
2. Update `WledConfig`:
   - Add `activeProfileId: String = PresetProfile.DEFAULT.id`.
3. Implement `ConfigSchemaMigrator` in `com.wled.tv.data`:
   - Treat legacy unversioned preferences as schema version 0.
   - Deterministically migrate v0 -> v1.
   - Support `exportConfigJson(config: WledConfig): String`.
   - Support `importConfigJson(jsonStr: String): WledConfig` with signature (`"app": "wled-tv"`) and schema version validation.
4. Unit tests in `ConfigRepositoryTest.kt` verifying preset loading, auto-switching, schema migration, and JSON export/import.

## Answer

Implemented the preset profile domain model and atomic configuration schema migration:

1. **`PresetProfile` Domain Model (`com.wled.tv.model.PresetProfile`)**:
   - Defined pure Kotlin data class with zero Android dependencies: `id`, `name`, `description`, and `calibration: ColorCalibration`.
   - Added built-in factory profiles:
     - `DEFAULT` ("Standard" baseline): uses default `ColorCalibration()`.
     - `CINEMA`: `saturation = 1.4f`, `contrast = 1.05f`, `smoothingFactor = 0.35f`, `blackThreshold = 12`.
     - `GAMING`: `saturation = 1.8f`, `contrast = 1.0f`, `smoothingFactor = 0.75f`, `blackThreshold = 6`.
     - `VIVID`: `saturation = 2.2f`, `contrast = 1.15f`, `smoothingFactor = 0.45f`, `blackThreshold = 4`.
     - `EYE_CARE`: `saturation = 1.2f`, `contrast = 0.9f`, `maxBrightness = 180`, `smoothingFactor = 0.25f`, `gainB = 0.85f`.
     - `CUSTOM`: `id = "custom"`, `name = "Custom"`.
   - Provided `BUILT_INS` list and case-insensitive fallback helper `PresetProfile.fromId(id)`.

2. **`WledConfig` Updates (`com.wled.tv.model.WledConfig`)**:
   - Added `activeProfileId: String = PresetProfile.DEFAULT.id`.
   - Added `activeProfile: PresetProfile` accessor.
   - Added `applyPreset(preset: PresetProfile): WledConfig` helper.

3. **`ConfigSchemaMigrator` (`com.wled.tv.data.ConfigSchemaMigrator`)**:
   - Managed `CURRENT_SCHEMA_VERSION = 1`, `KEY_SCHEMA_VERSION = "config_schema_version"`, `KEY_ACTIVE_PROFILE_ID = "active_profile_id"`, and `APP_SIGNATURE = "wled-tv"`.
   - Deterministic sequential migration logic (`v0` -> `v1`): detects unversioned or legacy schema (v0), writes version 1, and initializes `active_profile_id = "default"`.
   - Atomic export: `exportConfigJson(config)` produces versioned envelope with signature, version, timestamp, and serialized config payload.
   - Atomic import: `importConfigJson(jsonStr)` validates app signature and schema version, deserializing config or throwing `IllegalArgumentException`.
   - De-duplicated device and calibration JSON serialization/deserialization routines.

4. **`ConfigRepository` Integration**:
   - Integrated `ConfigSchemaMigrator.migrateIfNeeded(storage)` into `loadInitialConfig()`.
   - Added main-safe `suspend fun exportConfig(): String` and `suspend fun importConfig(jsonStr: String): Boolean`.
   - Persisted `KEY_SCHEMA_VERSION` and `KEY_ACTIVE_PROFILE_ID` on every configuration write.

5. **Test Suite**:
   - Added `PresetProfileTest` verifying all built-ins, calibration values, and fallback lookup.
   - Added `ConfigRepositoryTest` coverage for:
     - Default preset profile initialization.
     - Active profile switching, flow updates, and persistence across reloads.
     - `v0` to `v1` schema migration with `InMemoryConfigStorage`.
     - Valid JSON export and roundtrip import across multi-device configurations.
     - Rejection of invalid app signatures and unsupported future schema versions.
   - All JVM unit tests pass via `gradlew testDebugUnitTest`.
