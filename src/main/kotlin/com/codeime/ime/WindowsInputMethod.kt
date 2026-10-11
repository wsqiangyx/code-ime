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

/**
 * Windows 实现：
 * 1. 用 ImmGetConversionStatus 读取当前中英状态（NATIVE 位 = 中文）；
 * 2. 切换时先尝试 ImmSetConversionStatus / ImmSetOpenStatus 直接设置，
 *    约 80ms 后回读校验；
 * 3. 校验失败则降级为 java.awt.Robot 模拟用户配置的中英切换热键（微软拼音默认 Shift）。
 */
class WindowsInputMethod : InputMethodController {

    private val log = Logger.getInstance(WindowsInputMethod::class.java)
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

    override fun currentMode(): ImeMode? {
        if (!SystemInfo.isWindows) return null
        val lib = Imm32Library.INSTANCE ?: return null
        return withImc(lib) { himc ->
            when {
                himc == null -> ImeMode.ENGLISH // IME 关闭，按键直接走 ASCII
                else -> readConversion(lib, himc)
            }
        }
    }

    override fun switchTo(target: ImeMode): SwitchResult {
        if (!isAvailable()) return SwitchResult.FAILED
        if (currentMode() == target) return SwitchResult.NOOP

        val settings = CodeImeSettings.getInstance().state

        if (settings.useDirectImmSet && directSet(target)) {
            sleepQuietly(VERIFY_DELAY_MS)
            if (currentMode() == target) return SwitchResult.DIRECT
        }

        if (settings.hotkeyCode != 0 && pressHotkey(settings.hotkeyCode, settings.hotkeyModifiers)) {
            sleepQuietly(VERIFY_DELAY_MS)
            if (currentMode() == target) return SwitchResult.HOTKEY
        }

        return SwitchResult.FAILED
    }

    /** 直接设置转换模式；返回是否调用成功（不保证生效，需回读校验） */
    private fun directSet(target: ImeMode): Boolean {
        val lib = Imm32Library.INSTANCE ?: return false
        return withImc(lib) { himc ->
            if (himc == null) {
                false
            } else {
                val conversion = IntByReference()
                val sentence = IntByReference()
                if (!lib.ImmGetConversionStatus(himc, conversion, sentence)) {
                    false
                } else {
                    val newConversion = if (target == ImeMode.CHINESE) {
                        conversion.value or Imm32Library.IME_CMODE_NATIVE
                    } else {
                        conversion.value and Imm32Library.IME_CMODE_NATIVE.inv()
                    }
                    if (target == ImeMode.CHINESE) lib.ImmSetOpenStatus(himc, true)
                    lib.ImmSetConversionStatus(himc, newConversion, sentence.value)
                }
            }
        } ?: false
    }

    private fun readConversion(lib: Imm32Library, himc: Pointer): ImeMode? {
        val conversion = IntByReference()
        val sentence = IntByReference()
        return if (!lib.ImmGetConversionStatus(himc, conversion, sentence)) {
            null
        } else if (conversion.value and Imm32Library.IME_CMODE_NATIVE != 0) {
            ImeMode.CHINESE
        } else {
            ImeMode.ENGLISH
        }
    }

    private fun <T> withImc(lib: Imm32Library, block: (Pointer?) -> T): T? {
        val hwnd: HWND? = User32.INSTANCE.GetForegroundWindow()
        val himc = if (hwnd == null) null else lib.ImmGetContext(hwnd)
        return try {
            block(himc)
        } catch (t: Throwable) {
            log.warn("CodeIME: IMM32 调用异常", t)
            null
        } finally {
            if (hwnd != null && himc != null) {
                runCatching { lib.ImmReleaseContext(hwnd, himc) }
            }
        }
    }

    private fun pressHotkey(keyCode: Int, modifiers: Int): Boolean {
        val r = robot ?: return false
        val now = System.currentTimeMillis()
        if (now - lastHotkeyAt < HOTKEY_MIN_INTERVAL_MS) return false
        lastHotkeyAt = now

        val modifierKeys = listOf(
            InputEvent.CTRL_DOWN_MASK to KeyEvent.VK_CONTROL,
            InputEvent.ALT_DOWN_MASK to KeyEvent.VK_ALT,
            InputEvent.SHIFT_DOWN_MASK to KeyEvent.VK_SHIFT,
            InputEvent.META_DOWN_MASK to KeyEvent.VK_META,
        ).filter { modifiers and it.first != 0 }.map { it.second }

        synchronized(robotLock) {
            modifierKeys.forEach { r.keyPress(it) }
            r.keyPress(keyCode)
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
        private const val VERIFY_DELAY_MS = 80L
        private const val HOTKEY_MIN_INTERVAL_MS = 120L
    }
}
