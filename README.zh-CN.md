# CodeIME — 输入法自动切换（IDEA 插件 + VSCode 扩展）

**CodeIME** 是一套为中文开发者打造的输入法自动切换工具：**光标进入注释自动切中文输入法，回到代码自动切英文**，无需手动按 Shift。同一套策略的两个实现。

[English documentation (README.md)](README.md)

## 它解决什么问题

写代码时频繁在「写注释（中文）」和「写代码（英文）」之间切换，手动按 Shift 既繁琐又容易忘——刚切回代码忘了按 Shift，打出一串拼音；进了注释忘了切中文，注释全是英文。CodeIME 监听光标位置，判断你正处于注释还是代码，自动完成切换，全程无感。

## 两个交付物

| 交付物 | 位置 | 检测方式 | 输入法切换 |
|---|---|---|---|
| IntelliJ IDEA 插件（通用 PyCharm 等 JetBrains IDE） | 本目录（Gradle） | PSI `PsiComment`（所有语言通用） | JNA + imm32 / SendInput 原子热键兜底 |
| VSCode 扩展 | [`vscode/`](vscode/) | TextMate token scope（js/ts/css/html/java/python 及所有有语法的语言） | koffi + imm32 / SendInput 原子热键兜底 |

两者均支持：注释→中文、代码→英文、Python docstring（三引号）→中文、字符串可选中文、选中/失焦/控制台不切换、失败节流提醒、中英热键可配置。

## 环境要求

- Windows 10/11（切换输入法依赖 IMM32）
- 输入法：**微软拼音**（其他遵循 IMM32 标准的输入法可尝试，搜狗等第三方不保证）
- IDEA 2026.1+（build 261+）/ VSCode 1.90+

## 安装

### IDEA / PyCharm

构建后从磁盘安装：`Settings → Plugins → ⚙ → Install Plugin from Disk...`

```bash
./gradlew buildPlugin
# 产物：build/distributions/code-ime-<版本>.zip
```

### VSCode

```bash
cd vscode && npm install && npm run package
code --install-extension vscode/code-ime-<版本>.vsix
```

## 从源码构建

前置条件：IDEA 插件需要 JDK 21（Gradle 会经 foojay 自动下载，或在用户级 `~/.gradle/gradle.properties` 配置 `org.gradle.java.installations.paths=<本地 JDK21 路径>`）；VSCode 扩展需要 Node.js 18+。国内网络建议配置腾讯/npmmirror 镜像加速。

```bash
# IDEA 插件
./gradlew buildPlugin

# VSCode 扩展：类型检查 + 构建 + 27 个判定用例测试（需本机装有 VSCode）
cd vscode && npm install && npm run check && npm test && npm run package
```

## 工作原理

1. **上下文检测**：光标监听（30ms 防抖）——IDEA 版通过 PSI 树判断光标所在叶子是否 `PsiComment`；VSCode 版按 TextMate token scope 判定 `comment.*`。同行前后看处理"光标停在注释旁的缩进里"的场景；**空白行一律按代码处理**（块注释/docstring 内部的空行仍属注释）。
2. **状态检测**：JNA/koffi 调 `ImmGetConversionStatus`，`IME_CMODE_NATIVE` 位 = 中文模式。
3. **切换策略**：可选先 `ImmSetConversionStatus` 直接设置；失败降级为 **SendInput 原子注入**用户的中英切换热键（微软拼音默认 Shift）。原子注入保证真实按键不会插进注入序列（不会误组合出 Shift+方向键 选区、双击 Shift 全局搜索）；用户正物理按住 Shift/Ctrl/Alt 时跳过注入；微软拼音会忽略间隔过短的连续 Shift 切换，插件采用自适应节奏自动重试。
4. **防误切**：拖拽选区、IDE 失焦、控制台不切换；方向键快速连穿多个边界时过期切换请求作废。

## 已知限制

- 仅支持 Windows；macOS/Linux 已预留接口（`InputMethodController`）未实现。
- 模拟热键需与系统输入法的中/英切换热键一致（微软拼音默认 Shift）。
- 连续快速切换时第二次可能延迟最多约 2.5 秒（微软拼音防误触机制所致，插件会自动补按）。

## 排障诊断

`Help → Diagnostic Tools → Debug Log Settings` 添加 `CodeIME.Diag`，复现后在 `idea.log` 里 grep `CodeIME.Diag`——每次上下文判定、守卫拦截、切换尝试与回读校验均有记录。

## 参与贡献

见 [CONTRIBUTING.md](CONTRIBUTING.md)，中英文 Issue / PR 均欢迎。

## 开源协议

[GPL-3.0](LICENSE)（严格 copyleft）：任何基于本项目的二次分发必须同样以 GPL-3.0 开放源代码。

## 致谢 / 参考

- [InputSpark](https://github.com/BigBlueBaby/InputSpark)（MIT）：Windows 微软拼音 + IntelliJ 平台的开源先行实现，本项目的防抖/迟滞/热键降级策略参考了它的踩坑经验
- [Smart Input Pro](https://plugins.jetbrains.com/plugin/25280-smart-input-pro-chinese-)（闭源）：同类商业插件的交互设计参考
