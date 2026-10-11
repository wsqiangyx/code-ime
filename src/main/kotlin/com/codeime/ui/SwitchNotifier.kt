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

    /**
     * @param consecutiveFailures 连续失败次数；达到阈值才提醒，避免方向键移动时通知刷屏
     */
    fun notifyFailure(target: ImeMode, project: Project?, consecutiveFailures: Int) {
        if (!CodeImeSettings.getInstance().state.showFailureNotifications) return
        if (consecutiveFailures < NOTIFY_THRESHOLD) return
        val now = System.currentTimeMillis()
        if (now - lastNotifyAt < THROTTLE_MS) return
        lastNotifyAt = now

        val targetText = if (target == ImeMode.CHINESE) "中文" else "英文"
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup("CodeIME")
            .createNotification(
                "CodeIME",
                "连续 $consecutiveFailures 次无法自动切换到$targetText。请依次检查：" +
                    "① 设置中的模拟热键是否与系统输入法的中/英切换热键一致（微软拼音默认为 Shift）；" +
                    "② 若使用微软拼音，建议在设置中关闭「优先直接设置输入法状态」。",
                NotificationType.WARNING
            )
        notification.addAction(
            NotificationAction.createSimple("打开设置") {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, CodeImeConfigurable::class.java)
            }
        )
        notification.notify(project)
    }

    private const val NOTIFY_THRESHOLD = 3
    private const val THROTTLE_MS = 10_000L
}
