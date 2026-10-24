package com.codeime.ime

import com.codeime.listener.EditorCaretWatcher
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.sun.jna.Callback
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions

private const val WH_KEYBOARD_LL = 13
private const val WM_KEYDOWN = 0x0100
private const val WM_KEYUP = 0x0101
private const val WM_SYSKEYDOWN = 0x0104
private const val WM_SYSKEYUP = 0x0105
private const val LLKHF_INJECTED = 0x10
private const val VK_SHIFT = 0x10L
private const val WM_QUIT = 0x0012

/** user32.dll 的钩子相关绑定 */
interface HookUser32 : StdCallLibrary {
    fun SetWindowsHookExW(idHook: Int, lpfn: Callback, hMod: Pointer?, dwThreadId: Int): Pointer?
    fun UnhookWindowsHookEx(hhk: Pointer?): Boolean
    fun CallNextHookEx(hhk: Pointer?, nCode: Int, wParam: Long, lParam: Pointer?): Long
    fun GetMessageW(lpMsg: Pointer?, hWnd: Pointer?, wMsgFilterMin: Int, wMsgFilterMax: Int): Int
    fun PostThreadMessageW(idThread: Int, msg: Int, wParam: Long, lParam: Long): Boolean
    fun GetCurrentThreadId(): Int

    companion object {
        val INSTANCE: HookUser32? by lazy {
            try {
                Native.load("user32", HookUser32::class.java, W32APIOptions.UNICODE_OPTIONS)
            } catch (t: Throwable) {
                null
            }
        }
    }
}

/** 低级键盘钩子回调 */
interface KbdHookProc : Callback {
    fun callback(nCode: Int, wParam: Long, lParam: Pointer?): Long
}

/**
 * 低级键盘钩子（WH_KEYBOARD_LL）：在系统层面检测用户的物理按键。
 *
 * 在 TSF 架构的 IDE 上（PyCharm 2026.x），ImmGetContext 恒为 null，
 * 无法通过 IMM32 轮询检测手动切换。此钩子直接拦截键盘事件，
 * 通过 LLKHF_INJECTED 标志区分"插件 SendInput 注入的按键"和"用户物理按键"。
 *
 * 检测逻辑：用户按下并释放 Shift（期间没有按其他键）→ 手动切换了输入法。
 * 这与微软拼音的切换判定一致（Shift+其他键 = 组合键，不切换）。
 */
class KbdHookWatcher {

    private val log = Logger.getInstance("CodeIME.Diag")
    private var hookThread: Thread? = null
    private var hookHandle: Pointer? = null

    // 切换检测状态（钩子线程写，仅在此线程读，无需 volatile）
    private var shiftDown = false
    private var otherKeyPressed = false

    private val proc = object : KbdHookProc {
        override fun callback(nCode: Int, wParam: Long, lParam: Pointer?): Long {
            if (nCode >= 0 && lParam != null) {
                try {
                    val vkCode = lParam.getLong(0) and 0xFFFFFFFF
                    val flags = lParam.getInt(8)
                    val injected = (flags and LLKHF_INJECTED) != 0
                    val msg = wParam.toInt()

                    if (!injected) {
                        when {
                            vkCode == VK_SHIFT && (msg == WM_KEYDOWN || msg == WM_SYSKEYDOWN) -> {
                                shiftDown = true
                                otherKeyPressed = false
                            }
                            vkCode != VK_SHIFT && (msg == WM_KEYDOWN || msg == WM_SYSKEYDOWN) && shiftDown -> {
                                otherKeyPressed = true
                            }
                            vkCode == VK_SHIFT && (msg == WM_KEYUP || msg == WM_SYSKEYUP) -> {
                                if (shiftDown && !otherKeyPressed) {
                                    log.info("kbd hook: manual Shift toggle detected")
                                    onManualToggle()
                                }
                                shiftDown = false
                            }
                        }
                    }
                } catch (_: Throwable) {
                    // 钩子回调中绝不能抛异常（会阻塞全系统键盘）
                }
            }
            // 始终传递给下一个钩子——绝不拦截
            val u32 = HookUser32.INSTANCE ?: return 0
            return u32.CallNextHookEx(hookHandle, nCode, wParam, lParam)
        }
    }

    init {
        ApplicationManager.getApplication()?.let { app ->
            if (!app.isDisposed) app.invokeLater { start() }
        }
    }

    fun start() {
        if (hookThread != null) return
        val u32 = HookUser32.INSTANCE ?: return

        hookThread = Thread({
            try {
                val hook = u32.SetWindowsHookExW(WH_KEYBOARD_LL, proc, null, 0)
                if (hook == null) {
                    log.warn("CodeIME: 安装键盘钩子失败")
                    return@Thread
                }
                hookHandle = hook
                val tid = u32.GetCurrentThreadId()
                log.info("kbd hook installed, tid=$tid")

                // 消息泵（低级钩子的必要条件）
                val msgBuf = Memory(48)
                while (u32.GetMessageW(msgBuf, null, 0, 0) > 0) {
                    // 只需泵消息
                }
                u32.UnhookWindowsHookEx(hook)
                log.info("kbd hook removed")
            } catch (t: Throwable) {
                log.warn("CodeIME: 键盘钩子线程异常", t)
            }
        }, "CodeIME-KbdHook").apply {
            isDaemon = true
            start()
        }
    }

    /** 用户手动按了 Shift 切换输入法（钩子线程调用，调度到 EDT） */
    private fun onManualToggle() {
        ApplicationManager.getApplication()?.invokeLater {
            val app = ApplicationManager.getApplication() ?: return@invokeLater
            val controller = InputMethodManager.instance
            val newMode: ImeMode = when (controller) {
                is WindowsInputMethod -> controller.onUserManualToggle()
                else -> return@invokeLater
            }
            app.getService(EditorCaretWatcher::class.java)?.recordUserOverride(newMode)
        }
    }
}
