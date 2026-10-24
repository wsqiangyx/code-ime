package com.codeime.ui

import com.codeime.ime.ImeMode
import com.codeime.ime.InputMethodManager
import com.codeime.ime.WindowsInputMethod
import com.codeime.settings.CodeImeSettings
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.util.Alarm
import com.intellij.util.Consumer
import java.awt.event.MouseEvent
import javax.swing.JComponent

/**
 * 状态栏指示器：显示当前输入法模式（中/英），点击启用/禁用自动切换。
 * 由 CodeImeWidgetFactory 注册，每 2 秒轻量轮询刷新一次 IMM32 状态。
 */
class CodeImeWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation {

    private var statusBar: StatusBar? = null
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    override fun ID(): String = ID_VALUE

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        scheduleRefresh()
    }

    override fun dispose() {
        Disposer.dispose(alarm)
        statusBar = null
    }

    private fun scheduleRefresh() {
        alarm.cancelAllRequests()
        alarm.addRequest({
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                val bar = statusBar ?: return@invokeLater
                bar.updateWidget(ID_VALUE)
                if (!project.isDisposed) scheduleRefresh()
            }
        }, REFRESH_MS)
    }

    override fun getText(): String {
        if (!CodeImeSettings.getInstance().state.enabled) return "IME ✕"
        // displayMode：真实状态优先，TSF 架构下回退到虚拟状态（由键盘钩子保持同步）
        val mode = when (val c = InputMethodManager.instance) {
            is WindowsInputMethod -> c.displayMode()
            else -> c.currentMode()
        }
        return when (mode) {
            ImeMode.CHINESE -> "中"
            ImeMode.ENGLISH -> "英"
            null -> "IME"
        }
    }

    override fun getAlignment(): Float = JComponent.CENTER_ALIGNMENT

    override fun getTooltipText(): String = "CodeIME：当前输入法模式（点击启用/禁用自动切换）"

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer {
        val settings = CodeImeSettings.getInstance().state
        settings.enabled = !settings.enabled
    }

    companion object {
        const val ID_VALUE = "CodeIME.Widget"
        private const val REFRESH_MS = 2000L
    }
}
