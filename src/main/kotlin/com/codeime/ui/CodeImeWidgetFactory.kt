package com.codeime.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory

/**
 * 状态栏指示器工厂：Settings → Appearance → Status Bar Widgets 中可开关。
 */
class CodeImeWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = CodeImeWidget.ID_VALUE

    override fun getDisplayName(): String = "CodeIME 输入法模式"

    override fun createWidget(project: Project): StatusBarWidget = CodeImeWidget(project)

    override fun disposeWidget(widget: StatusBarWidget) {
        widget.dispose()
    }

    override fun canBeEnabledOn(statusBar: com.intellij.openapi.wm.StatusBar): Boolean = true

    override fun isAvailable(project: Project): Boolean = true
}
