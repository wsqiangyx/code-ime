# Contributing to CodeIME

Thanks for your interest! Issues and pull requests in **Chinese or English** are both welcome.

## Development setup

### IntelliJ IDEA plugin (Kotlin)

```bash
./gradlew buildPlugin      # build zip → build/distributions/
./gradlew runIde           # launch a sandbox IDE with the plugin
./gradlew sendInputProbe   # verify native SendInput binding (harmless F15 key)
```

Requires JDK 21 (provisioned automatically via toolchains, or set `org.gradle.java.installations.paths` in `~/.gradle/gradle.properties`).

### VS Code extension (TypeScript)

```bash
cd vscode
npm install
npm run check              # type-check
npm test                   # 27 context-detection cases against real VS Code grammars
npm run package            # build VSIX
```

`npm test` reads built-in grammars from a local VS Code installation; set the `VSCODE_EXTENSIONS`
env var to your `<VSCode>/resources/app/extensions` path if it is not installed at the default
location.

## Guidelines

- Keep changes surgical; discuss bigger refactors in an issue first.
- Windows/IMM32 behavior is subtle (thread affinity, TSF delays, hotkey quirks) — when fixing
  switch-related bugs, please include the `CodeIME.Diag` debug log from `idea.log` for the
  affected session.
- Add regression cases to `vscode/test/harness.ts` for any context-detection fix.
- Update `CHANGELOG.md` for user-visible changes.

## Releasing (maintainers)

1. Bump versions (`build.gradle.kts`, `vscode/package.json`) and update `CHANGELOG.md`.
2. `./gradlew buildPlugin` and `cd vscode && npm run package`.
3. `./gradlew publishPlugin` with `intellijPublishToken` set locally, or upload the zip manually
   at https://plugins.jetbrains.com .
4. Tag the release and push both to Gitee and GitHub.

## License

By contributing, you agree that your contributions will be licensed under the **GPL-3.0** license.
