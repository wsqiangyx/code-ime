package com.codeime.ui

import com.codeime.ime.ImeMode
import com.codeime.settings.CodeImeConfigurable
import com.codeime.settings.CodeImeSettings
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project

object SwitchNotifier {

    private var lastNotifyAt = 0L

    fun notifyFailure(target: ImeMode, project: Project?) {
        if (!CodeImeSettings.getInstance().state.showFailureNotifications) return
        val now = System.currentTimeMillis()
        if (now - lastNotifyAt < THROTTLE_MS) return
        lastNotifyAt = now

        val targetText = if (target == ImeMode.CHINESE) "中文" else "英文"
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup("CodeIME")
            .createNotification(
                "CodeIME",
                "无法自动切换到${targetText}。请确认设置中的模拟热键与系统输入法的中/英切换热键一致" +
                    "（微软拼音默认为 Shift）。",
                NotificationType.WARNING
            )
        notification.addAction(
            NotificationAction.createSimple("打开设置") {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, CodeImeConfigurable::class.java)
            }
        )
        notification.notify(project)
    }

    private const val THROTTLE_MS = 3000L
}
