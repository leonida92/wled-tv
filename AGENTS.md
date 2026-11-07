## Development & Testing

- **Fast test loop:** Run `cmd /c "gradlew.bat testDebugUnitTest"` to execute JVM unit tests in ~8 seconds without emulators.
- **TV deployment & updates via ADB:**
  - ADB binary: `$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe`
  - TV IP address: `192.168.1.50:5555`
  - Connect to TV:
    `& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" connect 192.168.1.50:5555`
  - Install / update APK on TV (using `-r` to replace existing and `-d` to allow downgrades):
    `& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s 192.168.1.50:5555 install -r -d app/build/outputs/apk/release/wled-tv-v<version>-release.apk`
  - Verify installed version on TV:
    `& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s 192.168.1.50:5555 shell dumpsys package com.wled.tv | Select-String "versionName"`
- **Coding standards:** See `CODING_STANDARDS.md` for reactive state, non-blocking UI, zero-allocation cadence loops, and architectural rules enforced during review.

## Architecture Roadmap & History

- **Pass 1 (v1.3.2):** In-process `AmbientStreamingEngine` and reactive `ConfigRepository` (see `docs/adr/0001-in-process-streaming-engine-and-reactive-config.md`).
- **Pass 2 (v1.3.3):** Unified `WledControllerClient` deep module and shared socket lifecycle (see `docs/adr/0002-unified-wled-controller-client.md`).
- **Pass 3 (v1.3.4):** Optical Color Pipeline Consolidation & pure byte transport (see `docs/adr/0003-optical-color-pipeline-and-wire-encoding.md`).
- **Pass 4 (Upcoming):** `FixtureGeometryEngine` — consolidating physical LED coordinate math, perimeter border calculations, aspect ratio crop math, and canvas layout vector generation.

## Agent skills

### Issue tracker

Local markdown files in `.scratch/<feature>/`. See `docs/agents/issue-tracker.md`.

### Triage labels

Default canonical roles (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`). See `docs/agents/triage-labels.md`.

### Domain docs

Single-context (`GLOSSARY.md` and `docs/adr/` at the repo root). See `docs/agents/domain.md` and `docs/adr/0001-in-process-streaming-engine-and-reactive-config.md`.
