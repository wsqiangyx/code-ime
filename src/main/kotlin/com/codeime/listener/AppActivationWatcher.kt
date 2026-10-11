package com.codeime.listener

import com.codeime.ime.ImeMode
import com.codeime.ime.InputMethodManager
import com.codeime.settings.CodeImeSettings
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.wm.IdeFrame
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.TimeUnit

/**
 * IDE 激活/失焦处理：
 * - 失焦时按设置切换（默认不干预）；
 * - 重新激活后延迟一小段时间，按当前光标上下文恢复输入法（规避 Alt+Tab 期间切换不稳的问题）。
 */
class AppActivationWatcher : ApplicationActivationListener {

    override fun applicationDeactivated(ideFrame: IdeFrame) {
        val target = CodeImeSettings.getInstance().deactivateTarget() ?: return
        // IDE 已失焦，此时 IMM32 面向的是前台窗口；仅在后台线程尝试，失败静默
        AppExecutorUtil.getAppScheduledExecutorService().execute {
            runCatching { InputMethodManager.instance.switchTo(target) }
        }
    }

    override fun applicationActivated(ideFrame: IdeFrame) {
        AppExecutorUtil.getAppScheduledExecutorService().schedule({
            ApplicationManager.getApplication().invokeLater {
                ApplicationManager.getApplication()
                    ?.getService(EditorCaretWatcher::class.java)
                    ?.resyncFocusedEditor()
            }
        }, RESYNC_DELAY_MS, TimeUnit.MILLISECONDS)
    }

    companion object {
        private const val RESYNC_DELAY_MS = 180L
    }
}
