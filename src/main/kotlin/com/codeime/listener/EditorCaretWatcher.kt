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
import java.util.concurrent.atomic.AtomicLong
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

    private val log = Logger.getInstance("CodeIME.Diag")
    private val switchExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "CodeIME-Switcher").apply { isDaemon = true }
    }
    private var started = false

    // 失败冷却：切换失败后短时间内不重试同一目标，避免反复模拟热键把输入法来回振荡
    @Volatile private var lastFailedTarget: ImeMode? = null
    @Volatile private var lastFailedAt = 0L
    @Volatile private var consecutiveFailures = 0

    // 切换请求代次：方向键快速连穿多个上下文时，只执行最后一次的切换请求
    private val switchGeneration = AtomicLong(0)

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
        // 拖拽/Shift 选区结束（选区清空）时光标没有移动事件，补一次判定
        val selectionListener = object : com.intellij.openapi.editor.event.SelectionListener {
            override fun selectionChanged(e: com.intellij.openapi.editor.event.SelectionEvent) {
                if (!editor.selectionModel.hasSelection()) {
                    onCaretChanged(editor)
                }
            }
        }
        editor.selectionModel.addSelectionListener(selectionListener)
        ATTACHED_LISTENER_KEY.set(editor, Pair(listener, selectionListener))
    }

    private fun detach(editor: Editor) {
        val listeners = editor.getUserData(ATTACHED_LISTENER_KEY) ?: return
        editor.caretModel.removeCaretListener(listeners.first)
        editor.selectionModel.removeSelectionListener(listeners.second)
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

    /** 编辑器标签页切换后按新编辑器光标位置重新判定（绕过防抖） */
    fun resyncEditor(editor: Editor) {
        if (editor.isDisposed) return
        val project = editor.project ?: return
        ApplicationManager.getApplication().invokeLater {
            if (!editor.isDisposed) evaluate(project, editor)
        }
    }

    private fun onCaretChanged(editor: Editor) {
        if (!CodeImeSettings.getInstance().state.enabled) return
        val project = editor.project ?: return
        log.debug(
            "caretChanged offset=${editor.caretModel.offset} " +
                "line=${editor.document.getLineNumber(editor.caretModel.offset)} " +
                "sel=${editor.selectionModel.hasSelection()} " +
                "focus=${editor.contentComponent.hasFocus()} " +
                "active=${ApplicationManager.getApplication()?.isActive()}"
        )
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
        if (!app.isActive()) { log.debug("evaluate skip: app inactive"); return }
        if (editor.isDisposed) { log.debug("evaluate skip: editor disposed"); return }
        if (editor.selectionModel.hasSelection()) { log.debug("evaluate skip: selection"); return }
        // 设置弹窗等抢焦点时不切换，模拟按键只会打进别的界面
        if (!editor.contentComponent.hasFocus()) { log.debug("evaluate skip: no focus"); return }
        val settings = CodeImeSettings.getInstance().state
        if (!settings.enabled) { log.debug("evaluate skip: disabled"); return }

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
        }
        val state = stateFor(editor)
        log.debug(
            "evaluate: offset=${editor.caretModel.offset} ctx=$context effective=$effective " +
                "target=${target ?: "NONE(不干预)"} last=${state.lastContext} " +
                "cooldown=${target != null && target == lastFailedTarget && System.currentTimeMillis() - lastFailedAt < FAILURE_COOLDOWN_MS}"
        )
        if (target == null) return
        if (effective == state.lastContext) return // 同一上下文内移动，不动作

        // 失败冷却：短时间内不对同一目标反复尝试
        if (target == lastFailedTarget && System.currentTimeMillis() - lastFailedAt < FAILURE_COOLDOWN_MS) {
            return
        }
        state.lastContext = effective

        val gen = switchGeneration.incrementAndGet()
        switchExecutor.execute {
            if (switchGeneration.get() != gen) {
                log.debug("switch skipped: stale (gen=$gen current=${switchGeneration.get()})")
                return@execute
            }
            try {
                val result = InputMethodManager.instance.switchTo(target)
                log.debug("switch result=$result target=$target")
                if (result == SwitchResult.FAILED) {
                    consecutiveFailures++
                    lastFailedTarget = target
                    lastFailedAt = System.currentTimeMillis()
                    ApplicationManager.getApplication().invokeLater {
                        // 清除“已尝试”标记，让下一次光标事件在冷却结束后重试
                        editor.getUserData(STATE_KEY)?.lastContext = null
                        SwitchNotifier.notifyFailure(target, project, consecutiveFailures)
                    }
                } else {
                    consecutiveFailures = 0
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
        private val ATTACHED_LISTENER_KEY =
            Key.create<Pair<CaretListener, com.intellij.openapi.editor.event.SelectionListener>>("CodeIME.caretListener")
        private val STATE_KEY = Key.create<EditorState>("CodeIME.editorState")
        private const val DEBOUNCE_MS = 30
        private const val FAILURE_COOLDOWN_MS = 1500
    }
}
