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

        val sincePlugin = System.currentTimeMillis() - InputMethodManager.instance.lastPluginSwitchAt()
        val attributedToPlugin = sincePlugin in 0..PLUGIN_ATTRIBUTION_MS
        lastKnown = mode
        if (attributedToPlugin) {
            log.debug("mode change -> plugin (since=$sincePlugin mode=$mode)")
        } else {
            log.debug("mode change -> user (since=$sincePlugin mode=$mode)")
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

        /** 插件注入后该窗口内的模式变化归因给插件（含校验轮询与自适应重试的时间跨度） */
        private const val PLUGIN_ATTRIBUTION_MS = 2500L
    }
}
