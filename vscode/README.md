# CodeIME — VSCode 输入法自动切换扩展

在 VSCode 中，**光标进入注释时自动切中文输入法，回到代码时自动切英文**，无需手动按 Shift。

- ✅ JavaScript / TypeScript / CSS / HTML / Java / Python 开箱即用（基于 TextMate 语法，所有已安装语法的语言通用）
- ✅ 注释（`//`、`/* */`、`#`、`<!-- -->`、Javadoc/docstring）：自动 **中文**
- ✅ 代码：自动 **英文**
- ✅ 字符串：默认不干预（可设置改为中文）
- ✅ SCM 提交信息输入框：按 **中文** 处理
- ✅ 状态栏显示当前输入法模式（中 / 英），点击可启停
- 🛡️ 选中文本、VSCode 失焦、输出面板等场景不切换，避免误触

## 环境要求

- Windows 10/11（切换输入法依赖 IMM32）
- 输入法：**微软拼音**（其他遵循 IMM32 的输入法可尝试，搜狗等第三方不保证）
- VSCode 1.90+

## 安装

扩展市场未上架前，从 VSIX 安装：

```bash
code --install-extension code-ime-1.1.0.vsix
```

或在 VSCode 中：扩展面板 → `...` → **从 VSIX 安装...**

## 设置

`Settings → 搜 CodeIME`：

| 设置项 | 默认 | 说明 |
|---|---|---|
| `codeIME.enabled` | `true` | 启用自动切换 |
| `codeIME.stringAsChinese` | `false` | 字符串内视为中文 |
| `codeIME.useDirectImmSet` | `false` | 优先直接设置输入法状态（关闭后优先模拟热键；微软拼音建议关闭） |
| `codeIME.toggleHotkey` | `shift` | 模拟切换热键：`shift` / `ctrl+space` / `none`，需与系统输入法设置一致 |
| `codeIME.onDeactivate` | `none` | VSCode 失焦时：不干预 / 切中文 / 切英文 |
| `codeIME.showFailureNotifications` | `true` | 切换失败时提醒 |

## 工作原理

1. **上下文检测**：通过 `vscode-textmate` + oniguruma 加载各语言的 TextMate 语法，对光标所在行做 token 分析（含行间状态，多行注释/字符串天然支持），按 token scope 判定 `comment.*` / `string.*` / 代码。行状态按文档版本增量缓存。
2. **输入法检测**：koffi 调用系统 `imm32.dll` 的 `ImmGetConversionStatus`（NATIVE 位 = 中文）。
3. **切换策略**：优先 `ImmSetConversionStatus` / `ImmSetOpenStatus` 直接设置 → 80ms 后回读校验 → 失败降级为 `keybd_event` 模拟用户配置的切换热键。
4. **防误切**：50ms 防抖、同一上下文内移动不重复切换、选中/失焦跳过、Alt+Tab 回焦后按当前上下文恢复。

## 已知限制

- 仅支持 Windows；macOS/Linux 不做输入法切换（状态栏显示 IME N/A）。
- 模拟热键方式要求设置中的热键与系统输入法的中/英切换热键一致（微软拼音默认 Shift）。
- JS 模板字符串 `${}` 内的嵌入代码 v1 仍按字符串处理。
- 无 TextMate 语法的语言（纯文本等）不切换。

## 开发

```bash
npm install            # 安装依赖
npm run check          # TypeScript 类型检查
npm test               # 构建并运行五语言判定测试（读取本机 VSCode 内置语法）
npm run package        # 打包 VSIX
```

测试用例位于 `test/harness.ts`，使用本机 VSCode 安装目录中的真实语法文件验证判定逻辑。

## 参考

- 同项目姊妹篇：IntelliJ IDEA 版插件（`../` 目录，基于 PSI 检测 + JNA/Robot 切换）
- [InputSpark](https://github.com/BigBlueBaby/InputSpark)：Windows 微软拼音 + IntelliJ 平台的开源先行实现

## 开源协议

本项目以 **GPL-3.0** 协议开源，详见 <https://gitee.com/wsqdz/code-ime/blob/master/LICENSE>。
