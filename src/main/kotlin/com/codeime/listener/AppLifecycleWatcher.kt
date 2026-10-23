package com.codeime.listener

import com.intellij.openapi.application.ApplicationManager
import com.intellij.ide.AppLifecycleListener

/**
 * 应用框架创建完成后启动监听（幂等）。
 * 注意：插件被动态加载/热更新时本回调不会触发，
 * 因此两个服务自身也会在初始化时自启动，这里只是兜底。
 */
class AppLifecycleWatcher : AppLifecycleListener {

    override fun appFrameCreated(commandLineArgs: MutableList<String>) {
        val app = ApplicationManager.getApplication() ?: return
        app.getService(EditorCaretWatcher::class.java)?.start()
        app.getService(com.codeime.ime.ImeModePoller::class.java)?.start()
    }
}
