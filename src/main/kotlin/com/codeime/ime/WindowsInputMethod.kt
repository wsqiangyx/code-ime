package com.codeime.ime

import com.codeime.settings.CodeImeSettings
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.SystemInfo
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.ptr.IntByReference
import java.awt.Robot
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
    private val robot: Robot? by lazy {
        try {
            Robot()
        } catch (t: Throwable) {
            log.warn("CodeIME: Robot 不可用，热键模拟降级关闭", t)
            null
        }
    }
    private val robotLock = Any()
    private var lastHotkeyAt = 0L

    override fun name() = "Windows IMM32"

    override fun isAvailable(): Boolean = SystemInfo.isWindows && Imm32Library.INSTANCE != null

    override fun currentMode(): ImeMode? = callOnEdt { currentModeInternal() }

    override fun switchTo(target: ImeMode): SwitchResult {
        if (!isAvailable()) { log.info("switchTo skip: unavailable"); return SwitchResult.FAILED }
        val current = callOnEdt { currentModeInternal() }
        log.info("switchTo target=$target current=$current")
        if (current == target) return SwitchResult.NOOP

        val settings = CodeImeSettings.getInstance().state

        if (settings.useDirectImmSet) {
            val directOk = callOnEdt { directSetInternal(target) }
            log.info("directSet ok=$directOk")
            if (directOk && verifyOnEdt(target)) return SwitchResult.DIRECT
        }

        // 微软拼音会忽略间隔过短的连续 Shift 切换：失败后自动再试一次（pressHotkey 内部会等待足够间隔）
        var hotkeyAttempts = 0
        while (hotkeyAttempts < 2 && settings.hotkeyCode != 0) {
            if (!pressHotkey(settings.hotkeyCode, settings.hotkeyModifiers)) break
            log.info("hotkey pressed attempt=$hotkeyAttempts code=${settings.hotkeyCode} mods=${settings.hotkeyModifiers}")
            if (verifyOnEdt(target)) return SwitchResult.HOTKEY
            hotkeyAttempts++
        }

        log.info("switchTo FAILED target=$target")
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
     * 单次 80ms 回读会造成误判（实际已切换却被判失败，进而反复模拟热键来回振荡）。
     */
    private fun verifyOnEdt(target: ImeMode): Boolean {
        var attempts = 0
        while (attempts < VERIFY_POLL_COUNT) {
            sleepQuietly(VERIFY_POLL_INTERVAL_MS)
            val mode = callOnEdt { currentModeInternal() }
            log.info("verify #$attempts mode=$mode want=$target")
            if (mode == target) return true
            attempts++
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
        val r = robot ?: return false
        // 微软拼音会忽略短间隔内的连续 Shift 切换：确保距上一次按键有足够间隔
        val sinceLast = System.currentTimeMillis() - lastHotkeyAt
        if (sinceLast in 0 until HOTKEY_REPEAT_DELAY_MS) {
            sleepQuietly(HOTKEY_REPEAT_DELAY_MS - sinceLast)
        }
        lastHotkeyAt = System.currentTimeMillis()

        val modifierKeys = listOf(
            InputEvent.CTRL_DOWN_MASK to KeyEvent.VK_CONTROL,
            InputEvent.ALT_DOWN_MASK to KeyEvent.VK_ALT,
            InputEvent.SHIFT_DOWN_MASK to KeyEvent.VK_SHIFT,
            InputEvent.META_DOWN_MASK to KeyEvent.VK_META,
        ).filter { modifiers and it.first != 0 }.map { it.second }

        synchronized(robotLock) {
            modifierKeys.forEach { r.keyPress(it) }
            r.keyPress(keyCode)
            sleepQuietly(HOTKEY_HOLD_MS) // 保持一小段按住时长，模拟人类按键节奏
            r.keyRelease(keyCode)
            modifierKeys.reversed().forEach { r.keyRelease(it) }
        }
        return true
    }

    private fun sleepQuietly(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        private const val VERIFY_POLL_COUNT = 6
        private const val VERIFY_POLL_INTERVAL_MS = 70L

        /** 微软拼音忽略短间隔内的连续 Shift 切换：两次模拟按键的最小间隔 */
        private const val HOTKEY_REPEAT_DELAY_MS = 2000L

        /** 模拟按键的按住时长，模拟人类节奏 */
        private const val HOTKEY_HOLD_MS = 40L
    }
}
