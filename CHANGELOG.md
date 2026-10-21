# Changelog

All notable changes to this project are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

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
