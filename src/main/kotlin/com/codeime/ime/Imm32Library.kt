package com.codeime.ime

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions

/**
 * imm32.dll 的 JNA 绑定。
 * JNA 本体由 IDE 提供（lib/ 下），本插件只做编译期声明，不打进插件包。
 */
interface Imm32Library : StdCallLibrary {
    fun ImmGetContext(hWnd: HWND?): Pointer?
    fun ImmReleaseContext(hWnd: HWND?, hIMC: Pointer?): Boolean
    fun ImmGetOpenStatus(hIMC: Pointer?): Boolean
    fun ImmSetOpenStatus(hIMC: Pointer?, fOpen: Boolean): Boolean
    fun ImmGetConversionStatus(hIMC: Pointer?, lpfdwConversion: IntByReference?, lpfdwSentence: IntByReference?): Boolean
    fun ImmSetConversionStatus(hIMC: Pointer?, fdwConversion: Int, fdwSentence: Int): Boolean

    companion object {
        const val IME_CMODE_NATIVE: Int = 0x0001
        const val IME_CMODE_FULLSHAPE: Int = 0x0004

        /** 非 Windows 或加载失败时为 null；W32 选项保证 Boolean↔Win32 BOOL(4字节) 的正确映射 */
        val INSTANCE: Imm32Library? by lazy {
            try {
                Native.load("imm32", Imm32Library::class.java, W32APIOptions.UNICODE_OPTIONS)
            } catch (t: Throwable) {
                null
            }
        }
    }
}

/**
 * user32.dll 的补充绑定：SendInput 用于原子注入完整的中英切换按键
 * （按下+松开一次性发出，用户按键不会插进按住窗口组合成其他快捷键），
 * GetAsyncKeyState 用于注入前检查用户是否正物理按住相关键。
 */
interface User32Ex : StdCallLibrary {
    fun SendInput(cInputs: Int, pInputs: Array<WinUser.INPUT>, cbSize: Int): Int
    fun GetAsyncKeyState(vKey: Int): Short

    companion object {
        /** 非 Windows 或加载失败时为 null */
        val INSTANCE: User32Ex? by lazy {
            try {
                Native.load("user32", User32Ex::class.java, W32APIOptions.UNICODE_OPTIONS)
            } catch (t: Throwable) {
                null
            }
        }
    }
}
