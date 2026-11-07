## Development & Testing

- **Fast test loop:** Run `cmd /c "gradlew.bat testDebugUnitTest"` to execute JVM unit tests in ~8 seconds without emulators.
- **TV deployment:** ADB binary is at `$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe`. Deploy to TV via `& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" connect 192.168.1.50:5555; & "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s 192.168.1.50:5555 install -r -d <apk-path>`.
- **Coding standards:** See `CODING_STANDARDS.md` for reactive state, non-blocking UI, and architectural rules enforced during review.

## Agent skills

### Issue tracker

Local markdown files in `.scratch/<feature>/`. See `docs/agents/issue-tracker.md`.

### Triage labels

Default canonical roles (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`). See `docs/agents/triage-labels.md`.

### Domain docs

Single-context (`GLOSSARY.md` and `docs/adr/` at the repo root). See `docs/agents/domain.md` and `docs/adr/0001-in-process-streaming-engine-and-reactive-config.md`.
