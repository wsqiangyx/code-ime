package com.codeime.ime

import com.codeime.settings.CodeImeSettings
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.SystemInfo
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.BaseTSD
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.WORD
import com.sun.jna.platform.win32.WinDef.DWORD
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.util.concurrent.CountDownLatch
import javax.swing.SwingUtilities

/**
 * Windows 实现：
 * 1. 用 ImmGetConversionStatus 读取当前中英状态（NATIVE 位 = 中文）；
 * 2. 切换时先尝试 ImmSetConversionStatus / ImmSetOpenStatus 直接设置，
 *    再轮询回读校验；
 * 3. 校验失败则降级为 java.awt.Robot 模拟用户配置的中英切换热键（微软拼音默认 Shift）。
 *
 * 重要：所有 IMM32 调用必须在 AWT 事件线程（EDT）上执行——IDEA 的窗口归 EDT 所有，
 * 从其他线程调用 ImmGetContext 会拿到 null，导致状态读取全部失效。
 */
class WindowsInputMethod : InputMethodController {

    private val log = Logger.getInstance("CodeIME.Diag")
    private var lastHotkeyAt = 0L

    @Volatile private var lastSwitchAt = 0L

    override fun lastPluginSwitchAt(): Long = lastSwitchAt

    override fun name() = "Windows IMM32"

    override fun isAvailable(): Boolean = SystemInfo.isWindows && Imm32Library.INSTANCE != null

    override fun currentMode(): ImeMode? = callOnEdt { currentModeInternal() }

    override fun switchTo(target: ImeMode): SwitchResult {
        if (!isAvailable()) { log.debug("switchTo skip: unavailable"); return SwitchResult.FAILED }
        val current = callOnEdt { currentModeInternal() }
        log.debug("switchTo target=$target current=$current")
        if (current == target) return SwitchResult.NOOP

        val settings = CodeImeSettings.getInstance().state

        if (settings.useDirectImmSet) {
            val directOk = callOnEdt { directSetInternal(target) }
            log.debug("directSet ok=$directOk")
            if (directOk) {
                lastSwitchAt = System.currentTimeMillis()
                if (verifyOnEdt(target)) return SwitchResult.DIRECT
            }
        }

        // 自适应节奏：微软拼音会忽略间隔过短的连续 Shift 切换，
        // 重试间隔 ≥800ms 也避开了 IDE「双击 Shift 全局搜索」的判定窗口。
        val retryWaits = longArrayOf(0L, 800L, 2000L)
        var attempt = 0
        while (attempt < retryWaits.size && settings.hotkeyCode != 0) {
            sleepQuietly(retryWaits[attempt])
            val pressed = pressHotkey(settings.hotkeyCode, settings.hotkeyModifiers)
            log.debug("hotkey pressed attempt=$attempt ok=$pressed")
            if (pressed) {
                lastSwitchAt = System.currentTimeMillis()
                if (verifyOnEdt(target)) return SwitchResult.HOTKEY
            }
            attempt++
        }

        log.debug("switchTo FAILED target=$target")
        return SwitchResult.FAILED
    }

    /** 当前输入法模式；null 表示无法判定。仅在 EDT 上调用 */
    private fun currentModeInternal(): ImeMode? {
        if (!SystemInfo.isWindows) return null
        val lib = Imm32Library.INSTANCE ?: return null
        val hwnd: HWND? = User32.INSTANCE.GetForegroundWindow()
        if (hwnd == null) return null
        val himc = lib.ImmGetContext(hwnd)
        if (himc == null) return ImeMode.ENGLISH // IME 关闭，按键直接走 ASCII
        try {
            val conversion = IntByReference()
            val sentence = IntByReference()
            if (!lib.ImmGetConversionStatus(himc, conversion, sentence)) return null
            return if (conversion.value and Imm32Library.IME_CMODE_NATIVE != 0) {
                ImeMode.CHINESE
            } else {
                ImeMode.ENGLISH
            }
        } finally {
            runCatching { lib.ImmReleaseContext(hwnd, himc) }
        }
    }

    /** 直接设置转换模式；返回调用是否成功（不保证生效，需回读校验）。仅在 EDT 上调用 */
    private fun directSetInternal(target: ImeMode): Boolean {
        val lib = Imm32Library.INSTANCE ?: return false
        val hwnd: HWND = User32.INSTANCE.GetForegroundWindow() ?: return false
        val himc = lib.ImmGetContext(hwnd) ?: return false
        try {
            val conversion = IntByReference()
            val sentence = IntByReference()
            if (!lib.ImmGetConversionStatus(himc, conversion, sentence)) return false
            val newConversion = if (target == ImeMode.CHINESE) {
                conversion.value or Imm32Library.IME_CMODE_NATIVE
            } else {
                conversion.value and Imm32Library.IME_CMODE_NATIVE.inv()
            }
            if (target == ImeMode.CHINESE) lib.ImmSetOpenStatus(himc, true)
            return lib.ImmSetConversionStatus(himc, newConversion, sentence.value)
        } finally {
            runCatching { lib.ImmReleaseContext(hwnd, himc) }
        }
    }

    /**
     * 轮询式校验：微软拼音经 TSF 应用新模式可能滞后数百毫秒，
     * 单次回读会造成误判。先密后疏（20/30/45/65/90ms）：
     * 大多数切换在 30~50ms 内生效，能被更早地确认。
     */
    private fun verifyOnEdt(target: ImeMode): Boolean {
        for (delay in VERIFY_POLL_DELAYS_MS) {
            sleepQuietly(delay.toLong())
            val mode = callOnEdt { currentModeInternal() }
            log.debug("verify mode=$mode want=$target after=${delay}ms")
            if (mode == target) return true
        }
        return false
    }

    /** 把 IMM32 调用调度到 EDT 执行并等待结果 */
    private fun <T> callOnEdt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        val latch = CountDownLatch(1)
        var result: Result<T>? = null
        SwingUtilities.invokeLater {
            result = runCatching(block)
            latch.countDown()
        }
        latch.await()
        return result!!.getOrThrow()
    }

    private fun pressHotkey(keyCode: Int, modifiers: Int): Boolean {
        val user32 = User32Ex.INSTANCE ?: return false

        // 用户正物理按住相关键时不注入：Shift+方向键会扩展选区、
        // 与手动 Shift 叠加会触发 IDE 的双击 Shift 全局搜索
        val modifierVks = listOf(
            InputEvent.CTRL_DOWN_MASK to KeyEvent.VK_CONTROL,
            InputEvent.ALT_DOWN_MASK to KeyEvent.VK_ALT,
            InputEvent.SHIFT_DOWN_MASK to KeyEvent.VK_SHIFT,
            InputEvent.META_DOWN_MASK to KeyEvent.VK_META,
        ).filter { modifiers and it.first != 0 }.map { it.second }
        if (isKeyHeld(keyCode) || modifierVks.any { isKeyHeld(it) }) return false

        // 两次注入保持最小间隔：避开微软拼音的忽略窗口与 IDE 双击 Shift 窗口
        val sinceLast = System.currentTimeMillis() - lastHotkeyAt
        if (sinceLast in 0 until HOTKEY_MIN_SPACING_MS) {
            sleepQuietly(HOTKEY_MIN_SPACING_MS - sinceLast)
        }
        // 等待期间用户可能按下，注入前再确认一次
        if (isKeyHeld(keyCode) || modifierVks.any { isKeyHeld(it) }) return false
        lastHotkeyAt = System.currentTimeMillis()

        // 原子批量注入：按下+松开在一次 SendInput 中发出，中间不会插入用户按键。
        // 注意：JNA 结构体数组必须用 toArray 分配连续内存，独立分配的元素会被 JNA 拒绝。
        val seq = ArrayList<Pair<Int, Int>>(modifierVks.size * 2 + 2)
        modifierVks.forEach { seq.add(it to 0) }
        seq.add(keyCode to 0)
        seq.add(keyCode to KEYEVENTF_KEYUP)
        modifierVks.reversed().forEach { seq.add(it to KEYEVENTF_KEYUP) }

        val events = WinUser.INPUT().toArray(seq.size) as Array<WinUser.INPUT>
        seq.forEachIndexed { i, (vk, flags) ->
            val input = events[i]
            input.type = DWORD(WinUser.INPUT.INPUT_KEYBOARD.toLong())
            input.input.setType("ki")
            val ki = WinUser.KEYBDINPUT()
            ki.wVk = WORD(vk.toLong())
            ki.wScan = WORD(0)
            ki.dwFlags = DWORD(flags.toLong())
            ki.time = DWORD(0)
            ki.dwExtraInfo = BaseTSD.ULONG_PTR(0)
            input.input.ki = ki
        }

        val sent = user32.SendInput(events.size, events, INPUT_SIZE)
        return sent == events.size
    }

    private fun isKeyHeld(vk: Int): Boolean {
        val user32 = User32Ex.INSTANCE ?: return false
        return (user32.GetAsyncKeyState(vk).toInt() and 0x8000) != 0
    }

    private fun sleepQuietly(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        /** 回读校验的间隔序列（毫秒）：先密后疏，累计约 250ms */
        private val VERIFY_POLL_DELAYS_MS = intArrayOf(20, 30, 45, 65, 90)

        /** 两次注入的最小间隔：避开微软拼音的忽略窗口与 IDE 双击 Shift 窗口 */
        private const val HOTKEY_MIN_SPACING_MS = 800L

        private const val KEYEVENTF_KEYUP = 0x0002
        private val INPUT_SIZE = WinUser.INPUT().size()
    }
}
