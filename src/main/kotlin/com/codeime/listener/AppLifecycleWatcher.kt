package com.codeime.listener

import com.intellij.openapi.application.ApplicationManager
import com.intellij.ide.AppLifecycleListener

/**
 * 应用框架创建完成后启动光标监听。
 */
class AppLifecycleWatcher : AppLifecycleListener {

    override fun appFrameCreated(commandLineArgs: MutableList<String>) {
        ApplicationManager.getApplication()?.getService(EditorCaretWatcher::class.java)?.start()
    }
}
