package com.codeime.ime

import com.codeime.listener.EditorCaretWatcher
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Disposer
import com.intellij.util.Alarm

/**
 * 输入法模式轮询器（250ms 轻量轮询）：
 * 检测到模式变化时，根据插件最近一次注入的时间把变化归因给插件或用户。
 * 归因给用户时，通知 EditorCaretWatcher 记录"手动覆盖"，
 * 自动切换在覆盖有效期内不再与用户抢输入法。
 */
class ImeModePoller {

    private val log = Logger.getInstance("CodeIME.Diag")
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, appDisposable())
    private var lastKnown: ImeMode? = null
    private var initialized = false
    private var started = false

    init {
        // 同 EditorCaretWatcher：插件动态加载时不触发 appFrameCreated，需在构造时自启动
        ApplicationManager.getApplication()?.let { app ->
            if (!app.isDisposed) app.invokeLater { start() }
        }
    }

    fun start() {
        if (started) return
        started = true
        schedule()
    }

    private fun schedule() {
        alarm.addRequest({
            poll()
            schedule()
        }, POLL_MS)
    }

    private fun poll() {
        val app = ApplicationManager.getApplication() ?: return
        if (!app.isActive()) return
        val mode = InputMethodManager.instance.currentMode() ?: return
        if (!initialized) {
            initialized = true
            lastKnown = mode
            return
        }
        if (mode == lastKnown) return

        // 归因：观察到的模式 == 插件的假定模式 → 是插件注入生效的；
        // 不等 → 一定是用户手动切的（无论距插件上次注入多久）。
        // 旧的时间窗口归因（2.5s）有严重误判：用户在插件注入后 2.5s 内手动切换，
        // 会被当成"插件自己干的"，跟手覆盖不记录 → 插件随后与用户抢输入法（1.5.8 实测）。
        val wm = InputMethodManager.instance as? WindowsInputMethod
        val assumed = wm?.assumedMode()
        val attributedToPlugin = (assumed == null || assumed == mode)
        lastKnown = mode
        if (attributedToPlugin) {
            if (wm != null) wm.syncAssumedMode(mode) // 插件生效，虚拟状态对齐真实
            log.debug("mode change -> plugin (observed=$mode assumed=$assumed)")
        } else {
            log.debug("mode change -> user (observed=$mode assumed=$assumed)")
            ApplicationManager.getApplication().invokeLater {
                ApplicationManager.getApplication()
                    ?.getService(EditorCaretWatcher::class.java)
                    ?.recordUserOverride(mode)
            }
        }
    }

    private fun appDisposable() = Disposer.newDisposable().also {
        Disposer.register(ApplicationManager.getApplication(), it)
    }

    companion object {
        private const val POLL_MS = 250L
    }
}
