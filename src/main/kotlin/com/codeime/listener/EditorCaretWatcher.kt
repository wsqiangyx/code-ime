package com.codeime.listener

import com.codeime.analyze.ContextAnalyzer
import com.codeime.analyze.EditorContext
import com.codeime.ime.ImeMode
import com.codeime.ime.InputMethodManager
import com.codeime.ime.SwitchResult
import com.codeime.settings.CodeImeSettings
import com.codeime.ui.SwitchNotifier
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import java.util.concurrent.Executors
import javax.swing.Timer

/**
 * 监听所有编辑器的光标变化，在上下文类型变化时切换输入法。
 *
 * 防误切设计：
 * - 50ms 防抖（Swing Timer，EDT 上触发）；
 * - 选中文本、IDE 失焦、无 PSI 编辑器时不动作；
 * - 同一上下文内移动光标不重复切换；
 * - PSI 解析滞后时用行内原始文本兜底（行内出现 "//" 视为注释）；
 * - 实际切换在单线程执行器上串行进行（内含校验等待）。
 */
class EditorCaretWatcher {

    private val log = Logger.getInstance(EditorCaretWatcher::class.java)
    private val switchExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "CodeIME-Switcher").apply { isDaemon = true }
    }
    private var started = false

    fun start() {
        if (started) return
        started = true
        val factory = EditorFactory.getInstance()
        factory.addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorCreated(event: EditorFactoryEvent) {
                attach(event.editor)
            }

            override fun editorReleased(event: EditorFactoryEvent) {
                detach(event.editor)
            }
        }, appDisposable())
        // 启动前已打开的编辑器
        factory.allEditors.forEach { attach(it) }
    }

    fun attach(editor: Editor) {
        if (editor.getUserData(ATTACHED_KEY) != null) return
        editor.putUserData(ATTACHED_KEY, true)
        val listener = object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) {
                onCaretChanged(event.editor)
            }
        }
        editor.caretModel.addCaretListener(listener)
        ATTACHED_LISTENER_KEY.set(editor, listener)
    }

    private fun detach(editor: Editor) {
        val listener = editor.getUserData(ATTACHED_LISTENER_KEY) ?: return
        editor.caretModel.removeCaretListener(listener)
        editor.getUserData(STATE_KEY)?.timer?.stop()
        editor.putUserData(ATTACHED_LISTENER_KEY, null)
    }

    /** IDE 重新激活后按当前上下文恢复输入法（绕过防抖） */
    fun resyncFocusedEditor() {
        ApplicationManager.getApplication().invokeLater {
            val projects = com.intellij.openapi.project.ProjectManager.getInstanceIfCreated()?.openProjects
            val editor = projects
                ?.firstNotNullOfOrNull { p ->
                    com.intellij.openapi.fileEditor.FileEditorManager.getInstance(p).selectedTextEditor
                } ?: return@invokeLater
            val project = editor.project ?: return@invokeLater
            evaluate(project, editor)
        }
    }

    private fun onCaretChanged(editor: Editor) {
        if (!CodeImeSettings.getInstance().state.enabled) return
        val project = editor.project ?: return
        val state = stateFor(editor)
        // 50ms 防抖：连续光标事件只保留最后一次
        val timer = state.timer ?: Timer(DEBOUNCE_MS) { evaluate(project, editor) }.also {
            it.isRepeats = false
            state.timer = it
        }
        timer.restart()
    }

    private fun evaluate(project: com.intellij.openapi.project.Project, editor: Editor) {
        val app = ApplicationManager.getApplication() ?: return
        if (!app.isActive()) return
        if (editor.isDisposed) return
        if (editor.selectionModel.hasSelection()) return
        val settings = CodeImeSettings.getInstance().state
        if (!settings.enabled) return

        val context = ReadAction.compute<EditorContext, RuntimeException> {
            ContextAnalyzer.analyze(editor, project)
        }

        // PSI 滞后兜底：PSI 认为是代码，但光标所在行前面已经出现了 "//"，按注释处理
        val effective = if (context == EditorContext.CODE && lineBeforeCaretContainsLineComment(editor)) {
            EditorContext.COMMENT
        } else {
            context
        }

        val target = when (effective) {
            EditorContext.COMMENT -> ImeMode.CHINESE
            EditorContext.STRING -> if (settings.stringAsChinese) ImeMode.CHINESE else null
            EditorContext.CODE -> ImeMode.ENGLISH
            EditorContext.UNKNOWN -> null
        } ?: return

        val state = stateFor(editor)
        if (effective == state.lastContext) return // 同一上下文内移动，不动作
        state.lastContext = effective

        switchExecutor.execute {
            try {
                val result = InputMethodManager.instance.switchTo(target)
                if (result == SwitchResult.FAILED) {
                    ApplicationManager.getApplication().invokeLater {
                        // 清除“已尝试”标记，让下一次光标事件重试，而不是卡在失败状态
                        editor.getUserData(STATE_KEY)?.lastContext = null
                        SwitchNotifier.notifyFailure(target, project)
                    }
                }
            } catch (t: Throwable) {
                log.warn("CodeIME: 切换输入法时出现异常", t)
            }
        }
    }

    private fun lineBeforeCaretContainsLineComment(editor: Editor): Boolean {
        val document = editor.document
        val offset = editor.caretModel.offset.coerceIn(0, document.textLength)
        val line = document.getLineNumber(offset)
        return document.getText(TextRange(document.getLineStartOffset(line), offset)).contains("//")
    }

    private fun stateFor(editor: Editor): EditorState {
        var state = editor.getUserData(STATE_KEY)
        if (state == null) {
            state = EditorState()
            editor.putUserData(STATE_KEY, state)
        }
        return state
    }

    private class EditorState {
        var timer: Timer? = null
        var lastContext: EditorContext? = null
    }

    private fun appDisposable() = Disposer.newDisposable().also {
        Disposer.register(ApplicationManager.getApplication(), it)
    }

    companion object {
        private val ATTACHED_KEY = Key.create<Boolean>("CodeIME.attached")
        private val ATTACHED_LISTENER_KEY = Key.create<CaretListener>("CodeIME.caretListener")
        private val STATE_KEY = Key.create<EditorState>("CodeIME.editorState")
        private const val DEBOUNCE_MS = 50
    }
}
