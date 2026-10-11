# CodeIME — 输入法自动切换（IDEA 插件 + VSCode 扩展）

**CodeIME** 是一套为中文开发者打造的输入法自动切换工具：**光标进入注释自动切中文输入法，回到代码自动切英文**，无需手动按 Shift。同一套策略的两个实现：

| 交付物 | 位置 | 检测方式 | 输入法切换 |
|---|---|---|---|
| IntelliJ IDEA 插件 | 本目录（Gradle） | PSI `PsiComment`（所有语言通用） | JNA + imm32 / Robot 热键兜底 |
| VSCode 扩展 | [`vscode/`](vscode/) | TextMate token scope（js/ts/css/html/java/python 及所有有语法的语言） | koffi + imm32 / keybd_event 热键兜底 |

两者均支持：注释→中文、代码→英文、字符串可选中文、选中/失焦/控制台不切换、失败节流提醒、中英热键可配置。

## 它解决什么问题

写代码时频繁在「写注释（中文）」和「写代码（英文）」之间切换，手动按 Shift 既繁琐又容易忘——刚切回代码忘了按 Shift，打出一串拼音；进了注释忘了切中文，注释全是英文。CodeIME 监听光标位置，判断你正处于注释还是代码，自动完成切换，全程无感。

## 环境要求

- Windows 10/11（切换输入法依赖 IMM32）
- 输入法：**微软拼音**（其他遵循 IMM32 标准的输入法可尝试，搜狗等第三方不保证）
- IDEA 2026.1+（build 261+）/ VSCode 1.90+

## 快速安装

### 构建前置条件

- IDEA 插件：本机有 JDK 21 时无需下载（在用户级 `~/.gradle/gradle.properties` 中配置 `org.gradle.java.installations.paths=<JDK21 路径>`）；没有也没关系，构建时 Gradle 会通过 foojay 自动下载
- VSCode 扩展：Node.js 18+
- 国内网络建议：Gradle 依赖与 VSIX 依赖可分别配置腾讯/npmmirror 镜像加速

### 构建与安装

```bash
# IDEA 插件（构建后从磁盘安装）
./gradlew buildPlugin
# 产物：build/distributions/code-ime-1.1.0.zip

# VSCode 扩展
cd vscode && npm install && npm run package
# 产物：vscode/code-ime-1.1.0.vsix，安装：code --install-extension <vsix 路径>
```

## 开源协议

本项目以 **GPL-3.0** 协议开源（严格 copyleft）：任何基于本项目的二次分发必须同样以 GPL-3.0 开放源代码。详见 [LICENSE](LICENSE)。

## 目录结构

```
├── build.gradle.kts / gradle.properties   # IDEA 插件构建（IntelliJ Platform Gradle Plugin）
├── src/main/kotlin/com/codeime/           # IDEA 插件源码（Kotlin）
│   ├── analyze/    # PSI 上下文判定
│   ├── ime/        # imm32 JNA 绑定与切换策略
│   ├── listener/   # 光标监听、防抖、IDE 激活处理
│   ├── settings/   # 设置持久化与界面
│   └── ui/         # 通知
├── vscode/                                # VSCode 扩展（TypeScript）
│   ├── src/tm/     # TextMate 语法加载与上下文判定
│   ├── src/ime/    # koffi imm32 绑定与切换策略
│   └── test/       # 独立判定测试（27 用例，读本机 VSCode 真实语法）
└── LICENSE                                # GPL-3.0 全文
```

---

# IDEA 插件 CodeIME — 注释/代码输入法自动切换

在 IntelliJ IDEA（及基于 IntelliJ Platform 的 IDE）中，**光标进入注释时自动切中文输入法，回到代码时自动切英文**，不再需要手动按 Shift 切换。

- ✅ 注释（`//`、`/* */`、Javadoc）：自动 **中文**
- ✅ 代码：自动 **英文**
- ✅ Git 提交信息编辑器（`COMMIT_EDITMSG`）：按 **中文** 处理
- ⚙️ 字符串内：默认不干预（可在设置中改为中文）
- 🛡️ 选中文本、IDE 失焦、控制台/终端等场景**不切换**，避免误触

## 环境要求

- Windows 10/11
- 输入法：**微软拼音**（其他遵循 IMM32 标准的输入法也可尝试，搜狗等第三方不保证）
- IntelliJ IDEA 2026.1+（build 261 及以上；其他版本需调整 `build.gradle.kts` 的 `sinceBuild` 重新构建）

## 安装

### 方式一：从磁盘安装（推荐）

1. 构建产物位于 `build/distributions/code-ime-1.1.0.zip`
2. 打开 IDEA → `Settings` → `Plugins` → 右上角齿轮 ⚙ → **Install Plugin from Disk...**
3. 选择上面的 zip，重启 IDE

### 方式二：沙箱调试运行

```bash
./gradlew runIde
```

会启动一个独立的沙箱 IDE 实例进行验证，不影响本机已安装的 IDEA。

## 使用

安装后即生效（默认开启）。可在 `Settings → Tools → CodeIME 输入法自动切换` 中调整：

| 设置项 | 说明 |
|---|---|
| 启用自动切换 | 总开关 |
| 字符串内视为中文 | 默认关闭（不干预） |
| 优先直接设置输入法状态 | 用 IMM32 直接设置（默认关闭：微软拼音下模拟热键更可靠，关闭后失败才降级为直接设置） |
| 中英切换模拟热键 | 与系统中输入法的中/英切换热键保持一致，**微软拼音默认 Shift**；点击输入框后按下组合键即可录制 |
| IDE 失焦时 | 不干预 / 切中文 / 切英文 |
| 切换失败时弹出提醒 | 建议开启，便于发现热键配置不匹配 |

## 工作原理

1. **上下文检测**：监听所有编辑器的光标变化（50ms 防抖），通过 PSI 树判断光标是否位于 `PsiComment`（对所有语言通用）；PSI 解析滞后时用行内原始文本 `//` 兜底；空行且上一行为 `//` 注释时视为注释延续。
2. **状态检测**：通过 JNA 调用系统 `imm32.dll` 的 `ImmGetConversionStatus`，`IME_CMODE_NATIVE` 位 = 中文模式。
3. **切换策略**：
   - 优先 `ImmSetConversionStatus` / `ImmSetOpenStatus` 直接设置，约 80ms 后回读校验；
   - 校验失败则降级为 `java.awt.Robot` 模拟用户配置的中英切换热键（微软拼音默认 Shift）。
4. **防误切**：选中文本、IDE 失焦时不切换；同一上下文内移动光标不重复切换；IDE 重新激活后延迟 180ms 按当前上下文恢复；切换失败以节流气泡提醒（3 秒最多一次）。

## 已知限制

- 仅支持 Windows；macOS/Linux 版本可基于 `InputMethodController` 接口扩展（切换逻辑因平台而异）。
- 模拟热键方式要求插件设置中的热键与系统中输入法的中/英切换热键**一致**。若你在微软拼音设置中关闭了 Shift 切换中英文，请在插件设置中录制你实际使用的组合键，或改用「直接设置」路径（默认优先）。
- 第三方输入法（搜狗等）对 IMM32 的响应不保证，本插件只对微软拼音做承诺。
- 若开启了系统「为每个应用窗口使用不同的输入法」选项，行为可能受系统每窗口记忆影响。
- 正按住 Shift 打大写字母、快速跨注释边界移动光标等极端场景下，模拟热键可能偶发与物理按键冲突；「优先直接设置」开启时可显著减少此类情况。
- **切换可能延迟 1~2 秒生效**：微软拼音会忽略短间隔内的连续 Shift 切换（防误触），因此插件在两次模拟按键之间会自动等待约 2 秒的安全间隔——晚一点切换，好过切不动。

## 开发

```bash
# 构建（产物在 build/distributions/）
./gradlew buildPlugin

# 沙箱 IDE 调试
./gradlew runIde

# 独立验证 imm32 JNA 绑定（只读当前输入法状态，不做切换）
./gradlew immProbe
```

- 构建对准本机 IDEA（`build.gradle.kts` 中 `intellijPlatform.local(...)`），JDK 使用 IDEA 自带 JBR（见 `gradle.properties`）。
- JNA 由 IDE 提供，仅编译期依赖，不打进插件包。

## 验证状态

- ✅ `./gradlew buildPlugin` 构建通过，zip 结构与 `plugin.xml`（`since-build=261`）正确
- ✅ 沙箱 `runIde` 启动日志显示 `Loaded custom plugins: CodeIME (1.0.0)`，启动全程 0 ERROR
- ✅ `./gradlew immProbe` 证明 imm32 绑定可加载、调用安全（交互会话中的前台窗口状态读取需在 GUI 中验证）
- ⏳ 输入法实际切换为交互行为，请按下方清单人工验证

## 人工验证清单

在沙箱 IDE（`./gradlew runIde`）或安装后的真实 IDE 中：

1. 新建 `Test.java`，光标停在代码处 → 应自动切到英文（打字母直接出 ASCII）
2. 输入 `//` 后继续输入 → 应自动切到中文（可直接打拼音出汉字）
3. 移动光标到下一行代码 → 应自动切回英文
4. 输入 `/* */` 并把光标移入中间 → 应自动切中文
5. 拖拽选中一段代码 → 不应弹出输入法切换（模拟 Shift 不干扰选区）
6. Alt+Tab 切走再切回 → 输入法按当前光标位置恢复
7. `Settings → Tools → CodeIME 输入法自动切换` → 面板可打开、修改后可保存重启不丢

若第 2/3 步无效：多半是模拟热键与系统不一致（微软拼音默认 Shift），打开设置检查热键项；也可尝试关闭「优先直接设置输入法状态」。

### 排障诊断

插件在日志类别 `CodeIME.Diag` 下输出 debug 级诊断日志（每次判定的上下文、守卫拦截原因、切换全过程）。需要排查时：IDE 菜单 `Help → Diagnostic Tools → Debug Log Settings` 添加 `CodeIME.Diag`，随后在 `idea.log` 中 grep `CodeIME.Diag` 即可。

## 致谢 / 参考

- [InputSpark](https://github.com/BigBlueBaby/InputSpark)（MIT）：Windows 微软拼音 + IntelliJ 平台的开源先行实现，本项目的防抖/迟滞/热键降级策略参考了它的踩坑经验
- [Smart Input Pro](https://plugins.jetbrains.com/plugin/25280-smart-input-pro-chinese-)（闭源）：同类商业插件的交互设计参考
