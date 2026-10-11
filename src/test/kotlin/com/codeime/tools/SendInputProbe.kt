package com.codeime.tools

import com.codeime.ime.Imm32Library
import com.codeime.ime.User32Ex
import com.sun.jna.platform.win32.BaseTSD
import com.sun.jna.platform.win32.WinDef.DWORD
import com.sun.jna.platform.win32.WinDef.WORD
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference

/**
 * 探针：验证 1.1.6 的 SendInput 原子注入与 GetAsyncKeyState 守卫是否正常。
 * 用无害的 F15 键测试注入（不会切换输入法），只读状态不模拟 Shift。
 * 运行：./gradlew sendInputProbe
 */
fun main() {
    val user32 = User32Ex.INSTANCE
    println("User32Ex loaded: ${user32 != null}")
    if (user32 == null) return

    println("GetAsyncKeyState(VK_SHIFT=16) = ${user32.GetAsyncKeyState(16)}")
    println("GetAsyncKeyState(VK_DOWN=40)  = ${user32.GetAsyncKeyState(40)}")

    val inputSize = WinUser.INPUT().size()
    println("sizeof(INPUT) = $inputSize (x64 期望 40)")

    // 无害键 F15(0x7E) 原子注入：按下+松开（JNA 结构体数组必须连续内存）
    val seq = listOf(0x7E to 0, 0x7E to 2)
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
    val sent = user32.SendInput(events.size, events, inputSize)
    println("SendInput(F15 down+up) sent=$sent (期望 2) -> ${if (sent == events.size) "注入 OK" else "注入失败"}")

    // imm32 状态读取
    val lib = Imm32Library.INSTANCE
    println("imm32 loaded: ${lib != null}")
    if (lib != null) {
        val hwnd = com.sun.jna.platform.win32.User32.INSTANCE.GetForegroundWindow()
        val himc = if (hwnd == null) null else lib.ImmGetContext(hwnd)
        if (himc != null) {
            val conv = IntByReference()
            val ok = lib.ImmGetConversionStatus(himc, conv, sent.let { IntByReference() })
            println("conversion ok=$ok NATIVE=${conv.value and 1 != 0}")
            lib.ImmReleaseContext(hwnd, himc)
        } else {
            println("himc=null（前台窗口无 IME 上下文）")
        }
    }
}
