package com.codeime.tools

import com.codeime.ime.Imm32Library
import com.sun.jna.platform.win32.User32
import com.sun.jna.ptr.IntByReference

/**
 * 独立探针：验证 Imm32Library 的 JNA 绑定在本机真实可用。
 * 只读取当前前台窗口的输入法状态，不做任何切换。
 * 运行：./gradlew immProbe
 */
fun main() {
    val lib = Imm32Library.INSTANCE
    println("imm32 loaded: ${lib != null}")
    if (lib == null) return

    val hwnd = User32.INSTANCE.GetForegroundWindow()
    println("foreground window: $hwnd")
    val himc = if (hwnd == null) null else lib.ImmGetContext(hwnd)
    if (himc == null) {
        println("HIMC = null（该窗口没有 IME 上下文，输入法未开启时属正常现象）")
        return
    }
    try {
        val conversion = IntByReference()
        val sentence = IntByReference()
        val ok = lib.ImmGetConversionStatus(himc, conversion, sentence)
        val open = lib.ImmGetOpenStatus(himc)
        println(
            "ImmGetConversionStatus ok=$ok" +
                " conversion=0x${Integer.toHexString(conversion.value)}" +
                " NATIVE(中文)=${conversion.value and Imm32Library.IME_CMODE_NATIVE != 0}" +
                " open(IME开启)=$open"
        )
    } finally {
        lib.ImmReleaseContext(hwnd, himc)
    }
}
