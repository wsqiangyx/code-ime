# Changelog

All notable changes to this project are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [1.5.2] - 2026-10-23

### Added
- **Character consistency in no-intervention contexts（字符一致性）**: when the context is
  "no-intervention" (strings by default), the plugin now follows the character before the caret —
  CJK characters/punctuation → Chinese, ASCII letters → English, digits/space/symbols → keep
  current. Example: type Chinese inside a string, move past the closing quote/brace → switches to
  English; move back next to the Chinese text → Chinese again.
- 9 char-mode cases (VSCode harness) + 9 assertions (IDEA toggleLogicProbe); total 51 + 31.

## [1.5.1] - 2026-10-23

### Fixed
- Manually-set Chinese inside a string stuck after leaving the string: when the current context was
  "no-intervention" (strings by default), `lastContext` was not updated, so moving from the string
  back to code was treated as "same context" and the switch to English was skipped. Context tracking
  now always updates; the follow-manual override is also recorded with the correct context.

## [1.4.5] - 2026-10-23

### Fixed
- **Switching never fired on newer IntelliJ platforms (PyCharm 2026.x).** Three independent guards
  each blocked every switch:
  1. `editor.contentComponent.hasFocus()` is permanently `false` under the Compose/Skiko renderer —
     replaced with an application-active + focused-window (non-dialog) + same-document check.
  2. `FileEditorManager.selectedTextEditor` can be a *different instance* for the same document,
     so identity comparison rejected the active editor — now compared by `Document`.
  3. When the window has no IMM32 context (e.g. a fullscreen game or a TSF-based editor),
     `ImmGetContext` returns null; the old code assumed "English", so it sent Shift on every event
     and oscillated. Now a null context is treated as *unknown*, the plugin tracks an assumed mode
     from its own last switch, and sends the hotkey only when the target actually differs.
- Console editors are explicitly skipped; `coerceIn` range crash on zero-line documents fixed.
- `lastSwitchAt` initialised to "now" instead of 0 (the epoch value made every mode change look like
  a manual user toggle, disabling auto switching for 60 s).

## [1.4.3] - 2026-10-23

### Fixed
- **Plugin silently stopped working after a marketplace hot-update.** Plugins installed/updated at
  runtime are dynamically loaded, which never fires `AppLifecycleListener.appFrameCreated` — the caret
  watcher and mode poller were only started there, so a hot-updated CodeIME never attached to editors.
  Both services now start themselves on construction (the lifecycle callback remains as an idempotent
  fallback), and editors that already carried a stale attach marker are re-attached.

## [1.4.2] - 2026-10-23

### Fixed
- `IllegalArgumentException: Cannot coerce value to an empty range` (unhandled EDT exception, reported by
  PyCharm with `PyRunFileInConsole`): some editors (e.g. the Python console) can report a document with
  zero lines; caret-line/offset clamping is now defensive.
- Console editors are explicitly skipped (half of the documented "no switching in consoles" behavior was
  implicit — Python console input editors have a PsiFile and were being evaluated).

## [1.4.1] - 2026-10-21

### Changed
- Faster switch confirmation: verification now polls at 20/30/45/65/90 ms (dense-to-sparse,
  ~250 ms total) instead of fixed 60 ms intervals — typical first-entry switch latency drops
  from ~110 ms to ~70–90 ms. Debounce shortened 30 ms → 20 ms.

## [1.4.0] - 2026-10-21

### Added
- **Follow manual toggles（跟手模式）**: when the user manually toggles the input method (e.g. pressing
  Shift inside a comment to type an English identifier), the plugin now attributes the mode change to
  the user and stops force-switching within the same context for 60 seconds. Leaving the context
  (comment → code) resumes normal auto switching. Implemented via a 250 ms mode poller that
  attributes changes using the plugin's last injection timestamp. Disable via
  `respectManualToggles` / 设置中的「优先跟随手动切换」.

## [1.3.0] - 2026-10-21

### Added
- IDEA: status bar widget showing the current IME mode (中/英); click toggles auto switching.
- IDEA: re-evaluate on editor tab switch (no caret event fires when switching tabs — the input method now follows the newly focused editor immediately).
- IDEA: re-evaluate after a drag/Shift selection is cleared.
- IDEA: best-effort detection of VCS commit-message editors (treated as Chinese).
- VS Code: support Remote-SSH/WSL/Dev Container windows (`vscode-remote` scheme).

## [1.2.0] - 2026-10-13

### Added
- Python docstrings (triple-quoted strings, including blank lines inside them) are now treated as comments → switches to Chinese.
- Plugin icons (light/dark) for JetBrains Marketplace.
- Marketplace publishing configuration (token injected via Gradle property or env var).
- CI workflow (GitHub Actions) for both deliverables.

### Changed
- Diagnostic logs lowered to debug level (enable via `Help → Diagnostic Tools → Debug Log Settings`, add `CodeIME.Diag`).

### Fixed
- Blank lines were misdetected as comments (caret line resolved via logical position; lookahead/back-lookahead strictly same-line; removed the "comment continuation" heuristic).
- Arrow-key switching failed when moving onto deeper-indented comment lines (caret rests in indentation whitespace).

## [1.1.7] - 2026-10-11

### Fixed
- SendInput injection failed 100% of the time: JNA structure arrays must use contiguous memory (`Structure.toArray`). Verified with an F15-key probe (`sent=2`).

## [1.1.6] - 2026-10-11

### Changed
- Hotkey simulation switched from `Robot` (press/hold/release) to atomic `SendInput` batch injection — real keystrokes can no longer interleave with injected ones.
- Injections are skipped while the user physically holds Shift/Ctrl/Alt (`GetAsyncKeyState`).
- Retry pacing widened to ≥800 ms to stay outside the double-Shift "Search Everywhere" window.

## [1.1.5] - 2026-10-11

### Changed
- Fixed 2-second wait before every hotkey press replaced with adaptive pacing: press immediately (~0.15 s typical), retry at 1.2 s / 2.4 s only when Microsoft Pinyin ignored the toggle.
- Verification failure window shortened 420 ms → 240 ms; debounce 50 ms → 30 ms.
- Stale switch requests are discarded when arrowing quickly through many boundaries.

## [1.1.4] - 2026-10-11

### Fixed
- Blank lines switched to Chinese: caret line is now resolved from the logical position; blank lines always count as code (blank lines inside block comments are still comments); removed the "blank line after `//` counts as comment continuation" heuristic.

## [1.1.3] - 2026-10-11

### Fixed
- Arrow-key switching: Microsoft Pinyin ignores consecutive Shift toggles issued <2 s apart. Human-like key pacing (40 ms hold, enforced spacing) and one automatic retry.

## [1.1.1] - 2026-10-11

### Fixed
- IMM32 thread affinity: `ImmGetContext` must be called on the window-owning thread (AWT EDT); previously called from a background thread and always returned null, causing oscillating switches and notification spam.
- Settings page rewritten with the IntelliJ UI DSL (was broken BoxLayout).
- "Prefer direct IMM32 set" now defaults to off (hotkey simulation is more reliable with Microsoft Pinyin).

## [1.1.0] - 2026-10-11

### Added
- VS Code extension: TextMate-based context detection (js/ts/css/html/java/python and any language with a grammar), koffi-based IMM32 switching, status bar indicator, 21-case context test suite.
- GPL-3.0 license; portable Gradle build (no machine-specific paths).

### Fixed
- Arrow-key caret moves onto indented comment lines failed to switch (same-line lookahead added).

## [1.0.0] - 2026-10-11

### Added
- Initial IntelliJ IDEA plugin: PSI-based comment/code detection, JNA `imm32` state reading, direct-set + Robot hotkey fallback, settings page, notifications.
