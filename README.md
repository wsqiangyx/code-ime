# CodeIME

[![Build](https://github.com/wsqiangyx/code-ime/actions/workflows/build.yml/badge.svg)](../../actions)
[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-blue.svg)](LICENSE)
[![Platform: Windows](https://img.shields.io/badge/Platform-Windows%2010%2F11-lightgrey.svg)](#requirements)

**CodeIME switches your input method automatically: Chinese inside comments, English in code.** No more manual Shift-toggling between writing comments and writing code.

[中文文档（README.zh-CN.md）](README.zh-CN.md)

## Why

While coding, you constantly switch between writing comments (Chinese) and writing code (English identifiers). Forgetting to toggle produces pinyin garbage in code or English-only comments. CodeIME watches your caret, decides whether you are in a comment or in code, and switches the input method for you — invisibly.

## Deliverables

| Target | Location | Context detection | IME switching |
|---|---|---|---|
| IntelliJ IDEA plugin (also PyCharm & other JetBrains IDEs) | this directory (Gradle) | PSI `PsiComment` (language-agnostic) | JNA `imm32` + hotkey fallback via `SendInput` |
| VS Code extension | [`vscode/`](vscode/) | TextMate token scopes (JS/TS/CSS/HTML/Java/Python and any language with a grammar) | koffi `imm32` + hotkey fallback via atomic `SendInput` |

Both support: comments → Chinese, code → English, optional Chinese inside strings, no switching while text is selected / the window is unfocused / in consoles, throttled failure notifications, and a configurable toggle hotkey. Python docstrings (`"""..."""`) count as comments. **Follow-manual-toggles**: if you manually switch the IME (e.g. Shift inside a comment to type an English identifier), the plugin stands down for 60 s in that context instead of fighting you.

## Requirements

- Windows 10/11 (IME switching relies on IMM32)
- IME: **Microsoft Pinyin** (other IMM32-compliant IMEs may work; third-party IMEs like Sogou are not guaranteed)
- IntelliJ Platform 2026.1+ (build 261+) or VS Code 1.90+

## Install

### IntelliJ IDEA / PyCharm

Build the plugin zip, then install from disk: `Settings → Plugins → ⚙ → Install Plugin from Disk...`

```bash
./gradlew buildPlugin
# artifact: build/distributions/code-ime-<version>.zip
```

### VS Code

```bash
cd vscode && npm install && npm run package
code --install-extension vscode/code-ime-<version>.vsix
```

## Building from source

Prerequisites: JDK 21 for the IDE plugin (Gradle provisions the toolchain automatically, or point `org.gradle.java.installations.paths` at a local JDK) and Node.js 18+ for the extension.

To build against your **installed** IntelliJ platform instead of downloading one (~1 GB), add to your user-level `~/.gradle/gradle.properties`:

```properties
localIdePath=D:/path/to/your/idea
# optional: org.gradle.java.installations.paths=D:/path/to/jdk-21
```

On slow networks, mirroring Gradle/npm registries helps.

```bash
# IDE plugin
./gradlew buildPlugin

# VS Code extension: type-check, build, and run the 27-case context tests
cd vscode && npm install && npm run check && npm test && npm run package
```

## How it works

1. **Context detection** — caret listeners (30 ms debounce): PSI leaf under the caret is a `PsiComment` (IDE), or the TextMate token scope is `comment.*` (VS Code). Forward/backward lookahead within the same line handles carets resting in indentation next to comments; blank lines always count as code (blank lines inside block comments/docstrings are still comments).
2. **IME state** — `ImmGetConversionStatus` via JNA/koffi: the `IME_CMODE_NATIVE` bit means Chinese.
3. **Switching** — try `ImmSetConversionStatus` directly (if enabled), then fall back to an **atomic `SendInput`** of the user's CN/EN toggle hotkey (Microsoft Pinyin: `Shift` by default). Atomic injection means real keystrokes can never combine with the injected ones (no accidental Shift+Arrow selection, no double-Shift Search Everywhere). Injections are skipped while you physically hold Shift/Ctrl/Alt, and retries use adaptive pacing because Microsoft Pinyin ignores rapid consecutive Shift toggles.
4. **Misfire guards** — no switching during selection drag, unfocused IDE, or in consoles; stale switch requests are discarded when you arrow through many boundaries quickly.

## Known limitations

- Windows only. macOS/Linux are architecturally prepared (`InputMethodController` interface) but not implemented.
- The simulated hotkey must match your system's CN/EN toggle hotkey (Microsoft Pinyin defaults to `Shift`).
- Consecutive rapid switches may be delayed up to ~2.5 s because Microsoft Pinyin ignores Shift toggles issued too close together; the plugin retries with adaptive pacing automatically.

## Troubleshooting

Enable debug diagnostics: `Help → Diagnostic Tools → Debug Log Settings` → add `CodeIME.Diag`, then reproduce and grep `CodeIME.Diag` in `idea.log`. Every decision (context, guard hits, each switch attempt and verification poll) is logged.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Issues and PRs in Chinese or English are both welcome.

## License

[GPL-3.0](LICENSE) — strict copyleft: derived works must be distributed under the same license with sources.

## Acknowledgments

- [InputSpark](https://github.com/BigBlueBaby/InputSpark) (MIT) — pioneering open-source IME switching for JetBrains IDEs on Windows; our debounce/hysteresis/hotkey-fallback design draws on its hard-won lessons.
- [Smart Input Pro](https://plugins.jetbrains.com/plugin/25280-smart-input-pro-chinese-) — interaction-design reference (closed source).
