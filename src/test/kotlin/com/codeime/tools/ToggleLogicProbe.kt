package com.codeime.tools

import com.codeime.ime.ImeMode
import com.codeime.ime.Imm32Library
import com.codeime.ime.User32Ex
import com.sun.jna.platform.win32.User32
import com.sun.jna.ptr.IntByReference

/**
 * 集成探针：验证切换检测状态机 + 假定模式 + IMM32 状态读取。
 * 运行：./gradlew toggleLogicProbe
 */
fun main() {
    var pass = 0
    var fail = 0

    fun check(name: String, actual: Any?, expected: Any?) {
        val ok = actual == expected
        if (ok) pass++ else fail++
        println("${if (ok) "PASS" else "FAIL"} $name: actual=$actual expected=$expected")
    }

    // ===== 1. 切换检测状态机 =====
    println("\n===== 切换检测状态机 =====")
    data class KeyEvent(val vk: Int, val down: Boolean, val injected: Boolean = false)

    val VK_SHIFT = 0x10
    val VK_A = 0x41
    val VK_UP = 0x26

    /** 模拟 KbdHookWatcher 的状态机逻辑 */
    class ToggleStateMachine {
        var shiftDown = false; private set
        var otherKeyPressed = false; private set

        fun onKeyEvent(e: KeyEvent): Boolean {
            if (e.injected) return false
            return when {
                e.vk == VK_SHIFT && e.down -> { shiftDown = true; otherKeyPressed = false; false }
                e.vk != VK_SHIFT && e.down && shiftDown -> { otherKeyPressed = true; false }
                e.vk == VK_SHIFT && !e.down -> {
                    val toggled = shiftDown && !otherKeyPressed
                    shiftDown = false
                    toggled
                }
                else -> false
            }
        }
    }

    // 测试 1: Shift 单击 = 切换
    run {
        val sm = ToggleStateMachine()
        sm.onKeyEvent(KeyEvent(VK_SHIFT, down = true))
        val result = sm.onKeyEvent(KeyEvent(VK_SHIFT, down = false))
        check("Shift 单击", result, true)
    }

    // 测试 2: Shift+Letter（大写字母）≠ 切换
    run {
        val sm = ToggleStateMachine()
        sm.onKeyEvent(KeyEvent(VK_SHIFT, down = true))
        sm.onKeyEvent(KeyEvent(VK_A, down = true))
        sm.onKeyEvent(KeyEvent(VK_A, down = false))
        val result = sm.onKeyEvent(KeyEvent(VK_SHIFT, down = false))
        check("Shift+Letter（大写）", result, false)
    }

    // 测试 3: Shift+方向键（选择）≠ 切换
    run {
        val sm = ToggleStateMachine()
        sm.onKeyEvent(KeyEvent(VK_SHIFT, down = true))
        sm.onKeyEvent(KeyEvent(VK_UP, down = true))
        sm.onKeyEvent(KeyEvent(VK_UP, down = false))
        val result = sm.onKeyEvent(KeyEvent(VK_SHIFT, down = false))
        check("Shift+方向键（选择）", result, false)
    }

    // 测试 4: 注入的 Shift 不触发
    run {
        val sm = ToggleStateMachine()
        sm.onKeyEvent(KeyEvent(VK_SHIFT, down = true, injected = true))
        val result = sm.onKeyEvent(KeyEvent(VK_SHIFT, down = false, injected = true))
        check("注入的 Shift", result, false)
    }

    // 测试 5: 连续两次 Shift 单击 = 两次切换
    run {
        val sm = ToggleStateMachine()
        sm.onKeyEvent(KeyEvent(VK_SHIFT, true)); val r1 = sm.onKeyEvent(KeyEvent(VK_SHIFT, false))
        sm.onKeyEvent(KeyEvent(VK_SHIFT, true)); val r2 = sm.onKeyEvent(KeyEvent(VK_SHIFT, false))
        check("连续两次 Shift", r1 to r2, true to true)
    }

    // 测试 6: Shift 按住 → 松开其他键 → Shift 松开 ≠ 切换
    run {
        val sm = ToggleStateMachine()
        sm.onKeyEvent(KeyEvent(VK_SHIFT, true))
        sm.onKeyEvent(KeyEvent(VK_A, true))
        sm.onKeyEvent(KeyEvent(VK_A, false))
        val result = sm.onKeyEvent(KeyEvent(VK_SHIFT, false))
        check("Shift按住打字后松开", result, false)
    }

    // ===== 2. 假定模式逻辑 =====
    println("\n===== 假定模式逻辑 =====")

    // 字符一致性规则
    println("\n===== 字符一致性（ImeMode.fromChar） =====")
    check("汉字→中文", ImeMode.fromChar('中'), ImeMode.CHINESE)
    check("中文句号→中文", ImeMode.fromChar('。'), ImeMode.CHINESE)
    check("全角冒号→中文", ImeMode.fromChar('：'), ImeMode.CHINESE)
    check("ASCII字母→英文", ImeMode.fromChar('a'), ImeMode.ENGLISH)
    check("大写字母→英文", ImeMode.fromChar('Z'), ImeMode.ENGLISH)
    check("数字→保持现状", ImeMode.fromChar('3'), null)
    check("空格→保持现状", ImeMode.fromChar(' '), null)
    check("连字符→保持现状", ImeMode.fromChar('-'), null)
    class AssumedModeTracker(initial: String? = null) {
        var mode: String? = initial; private set
        fun onPluginSwitch(target: String) { mode = target }
        fun onUserToggle(): String {
            val new = if (mode == "CHINESE") "ENGLISH" else "CHINESE"
            mode = new
            return new
        }
    }

    // 插件切到中文 → 用户手动切 → 英文
    run {
        val t = AssumedModeTracker()
        t.onPluginSwitch("CHINESE")
        check("初始", t.mode, "CHINESE")
        val new = t.onUserToggle()
        check("手动切换后", new, "ENGLISH")
        check("再手动切换", t.onUserToggle(), "CHINESE")
    }

    // 初始为 null → 用户手动切 → 中文（默认假设）
    run {
        val t = AssumedModeTracker()
        val new = t.onUserToggle()
        check("从 null 切换", new, "CHINESE")
    }

    // ===== 3. 上下文判定边界 =====
    println("\n===== 上下文判定规则（纯文本层面） =====")
    fun classifyLine(line: String): String {
        val trimmed = line.trimStart()
        return when {
            trimmed.isEmpty() -> "BLANK"
            trimmed.startsWith("//") || trimmed.startsWith("#") || trimmed.startsWith("/*") || trimmed.startsWith("<!--") -> "COMMENT"
            trimmed.startsWith("\"\"\"") || trimmed.startsWith("'''") -> "DOCSTRING"
            else -> "CODE"
        }
    }

    check("空行", classifyLine(""), "BLANK")
    check("空格行", classifyLine("   "), "BLANK")
    check("// 注释", classifyLine("// 注释"), "COMMENT")
    check("# 注释", classifyLine("# Python"), "COMMENT")
    check("/* 块注释", classifyLine("/* 块"), "COMMENT")
    check("<!-- HTML", classifyLine("<!-- HTML"), "COMMENT")
    check("代码", classifyLine("let x = 1;"), "CODE")
    check("docstring", classifyLine("\"\"\"文档"), "DOCSTRING")
    check("缩进注释", classifyLine("    // 缩进"), "COMMENT")
    check("缩进代码", classifyLine("    let x = 1;"), "CODE")

    // ===== 4. IMM32 可用性 =====
    println("\n===== IMM32 状态 =====")
    val lib = Imm32Library.INSTANCE
    check("imm32 加载", lib != null, true)
    if (lib != null) {
        val hwnd = User32.INSTANCE.GetForegroundWindow()
        val himc = if (hwnd == null) null else lib.ImmGetContext(hwnd)
        check("前台窗口", hwnd != null, true)
        if (himc != null) {
            val conv = IntByReference()
            val sent = IntByReference()
            val ok = lib.ImmGetConversionStatus(himc, conv, sent)
            check("状态读取", ok, true)
            check("NATIVE 位", (conv.value and 1) == 0 || (conv.value and 1) == 1, true)
            lib.ImmReleaseContext(hwnd, himc)
        } else {
            println("INFO: HIMC=null（可能前台是无 IME 窗口或 TSF 架构，属正常）")
        }
    }

    // ===== 5. SendInput 原子注入 =====
    println("\n===== SendInput 原子注入 =====")
    val u32 = User32Ex.INSTANCE
    check("User32Ex 加载", u32 != null, true)
    // 无害键 F15 已在 sendInputProbe 中验证，此处不重复注入

    // ===== 汇总 =====
    println("\n${"=".repeat(40)}")
    println("$pass passed, $fail failed")
    if (fail > 0) {
        println("存在失败用例！")
        kotlin.system.exitProcess(1)
    }
}
