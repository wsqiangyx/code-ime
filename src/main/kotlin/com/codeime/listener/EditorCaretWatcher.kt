package com.codeime.listener

import com.codeime.analyze.ContextAnalyzer
import com.codeime.analyze.EditorContext
import com.codeime.ime.ImeMode
import com.codeime.ime.InputMethodManager
import com.codeime.ime.SwitchResult
import com.codeime.settings.CodeImeSettings
import com.codeime.ui.SwitchNotifier
import com.intellij.openapi.application.ApplicationManager
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

    // 跟手覆盖：用户手动切换后，有效期内同语境让位（语境=null 表示短暂全局让位）。
    // 用户意图（“我现在要用英文打字”）以当前所在位置为准；语境变化（移出注释又移入）
    // 后自动恢复——既保护注释里切英文写标识符的需求，又不锁死其他语境的自动切换
    @Volatile private var userOverrideMode: ImeMode? = null
    @Volatile private var userOverrideAt = 0L
    @Volatile private var userOverrideContext: EditorContext? = null

    // 切换请求代次：方向键快速连穿多个上下文时，只执行最后一次的切换请求
    private val switchGeneration = AtomicLong(0)

    init {
        // 在服务初始化时自启动：插件被动态加载（市场热更新）时不会触发
        // AppLifecycleListener.appFrameCreated，只依赖它会导致监听器永远不启动。
        ApplicationManager.getApplication()?.let { app ->
            if (!app.isDisposed) app.invokeLater { start() }
        }
    }

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
        // 动态加载前已存在的编辑器可能残留旧的 ATTACHED 标记（userData 跨版本共享），
        // 此时需要强制重新挂载
        factory.allEditors.forEach { editor ->
            editor.putUserData(ATTACHED_KEY, null)
            attach(editor)
        }
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

    /**
     * 用户手动切换了输入法（由 KbdHookWatcher 检测到物理 Shift 按下后调用）：
     * 记录覆盖（语境级：发生在哪个语境就保护哪个语境；在对话框/搜索框里发生时
     * 记 null = 短暂全局让位）。有效期内同语境不与用户抢——注释里切英文写标识符
     * 是正当需求，插件不得抢回中文。
     */
    fun recordUserOverride(mode: ImeMode) {
        if (!CodeImeSettings.getInstance().state.respectManualToggles) return
        userOverrideMode = mode
        userOverrideAt = System.currentTimeMillis()
        // 记录发生时的语境：取当前焦点主编辑器的判定语境；
        // 拿不到（对话框/搜索框）则记 null = 短暂全局让位
        userOverrideContext = currentFocusedContext()
        // 同步假定模式：TSF 架构下 IMM32 读不到真实状态，虚拟状态必须跟随用户动作
        (InputMethodManager.instance as? com.codeime.ime.WindowsInputMethod)
            ?.syncAssumedMode(mode)
        log.debug("user override recorded: mode=$mode ctx=$userOverrideContext")
    }

    /** 当前焦点主编辑器的语境（快速判定：光标前字符 / 行首特征），拿不到返回 null */
    private fun currentFocusedContext(): EditorContext? {
        val projects = com.intellij.openapi.project.ProjectManager.getInstanceIfCreated()?.openProjects
        val editor = projects
            ?.firstNotNullOfOrNull { p ->
                com.intellij.openapi.fileEditor.FileEditorManager.getInstance(p).selectedTextEditor
            } ?: return null
        val project = editor.project ?: return null
        return runCatching {
            com.intellij.openapi.application.WriteIntentReadAction.compute<EditorContext> {
                ContextAnalyzer.analyze(editor, project)
            }
        }.getOrNull()
    }

    /**
     * 编辑器是否处于可切换的焦点语境。
     * 不用 contentComponent.hasFocus()：PyCharm 2026.x 的 Compose/Skiko 渲染下恒为 false。
     */
    private fun isEditorContextFocused(
        project: com.intellij.openapi.project.Project,
        editor: Editor,
    ): Boolean {
        val app = ApplicationManager.getApplication() ?: return false
        if (!app.isActive()) return false

        // 1) 必须是当前选中的文件：后台标签页/工具窗口里的编辑器不应触发切换。
        // 注意用文件比较而非编辑器对象同一性——PyCharm 2026.x 中 selectedTextEditor
        // 可能返回与监听器持有的不同实例，对象比较会把一切拦死。
        val selected = com.intellij.openapi.fileEditor.FileEditorManager
            .getInstance(project).selectedTextEditor
        if (selected != null && selected !== editor) {
            val selectedFile = selected.document
            if (selectedFile !== editor.document) {
                log.debug("focus check: not selected file (${editor.document.hashCode()})")
                return false
            }
        }

        // 2) 当前焦点必须在 IDE 的普通窗口上（对话框/弹窗抢焦点时跳过，避免模拟按键打进对话框）
        val focused = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().focusedWindow
        if (focused == null) {
            log.debug("focus check: focusedWindow=null")
            return false
        }
        if (!focused.isShowing) {
            log.debug("focus check: not showing (${focused.javaClass.simpleName})")
            return false
        }
        if (isDialog(focused)) {
            log.debug("focus check: dialog focused (${focused.javaClass.simpleName})")
            return false
        }
        return true
    }

    private fun isDialog(window: java.awt.Window): Boolean {
        var w: java.awt.Window? = window
        var depth = 0
        while (w != null && depth < 6) {
            if (w is java.awt.Dialog) return true
            w = w.owner
            depth++
        }
        return false
    }

    /**
     * 字符一致性目标：跟随光标前一个字符的类型。
     * 中文字符/中文标点 → 中文；ASCII 字母 → fallback（英文）；数字/空格/符号 → null（不干预）。
     * 用于字符串语境（默认不干预）与代码语境（刚打完中文接续时不打断）。
     */
    /**
     * 字符一致性目标：跟随光标前一个字符的类型。
     * cjkOnly=true（字符串语境）：仅 CJK 前字符返回中文，其余一律 null（静默）。
     * cjkOnly=false（代码语境）：CJK → 中文（接续）；ASCII 字母 → fallback（默认英文）；
     * 数字/空格/符号 → null。
     * 组字保护：微软拼音组字中（"中zhong"）返回 null——此时必须完全静默。
     */
    private fun charConsistencyTarget(editor: Editor, fallback: ImeMode?, cjkOnly: Boolean): ImeMode? {
        val offset = editor.caretModel.offset
        if (offset <= 0) return null
        val seq = editor.document.charsSequence
        val prev = seq[offset - 1]
        val prev2 = if (offset >= 2) seq[offset - 2] else null
        if (ImeMode.isComposingText(prev, prev2)) return null
        val fromChar = ImeMode.fromChar(prev)
        return when {
            fromChar == ImeMode.CHINESE -> ImeMode.CHINESE
            cjkOnly -> null
            else -> fromChar ?: fallback
        }
    }

    private fun onCaretChanged(editor: Editor) {
        if (!CodeImeSettings.getInstance().state.enabled) return
        val project = editor.project ?: return
        if (log.isDebugEnabled) {
            // 注意：不要记录 contentComponent.hasFocus()——2026.x 的 Compose 渲染下恒为 false，
            // 真实焦点判定见 isEditorContextFocused()
            log.debug(
                "caretChanged file=${editor.virtualFile?.name} kind=${editor.editorKind} " +
                    "offset=${editor.caretModel.offset} " +
                    "line=${editor.document.getLineNumber(editor.caretModel.offset)} " +
                    "sel=${editor.selectionModel.hasSelection()} " +
                    "active=${ApplicationManager.getApplication()?.isActive()}"
            )
        }
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
        // 只在主编辑器切换。搜索框、提交对话框、弹窗等（UNTYPED）与控制台（CONSOLE）
        // 完全交给用户掌控——这些地方用户意图明确，插件抢输入法只会帮倒忙（1.5.4 用户反馈）
        when (editor.editorKind) {
            com.intellij.openapi.editor.EditorKind.MAIN_EDITOR -> { /* 主编辑区：正常判定 */ }
            com.intellij.openapi.editor.EditorKind.CONSOLE ->
                { log.debug("evaluate skip: console editor"); return }
            else -> { log.debug("evaluate skip: editor kind=${editor.editorKind}"); return }
        }
        if (editor.selectionModel.hasSelection()) { log.debug("evaluate skip: selection"); return }
        // 设置弹窗/对话框抢焦点时不切换，避免模拟按键打进别的界面。
        // 注意：不能用 editor.contentComponent.hasFocus()——在 PyCharm 2026.x 的
        // Compose/Skiko 渲染架构下它恒为 false，会把所有切换拦死（1.4.3 及更早的真实故障）。
        // 改为判断：IDE 处于激活状态 + 当前焦点窗口属于本应用 + 该编辑器所在文件是当前选中文件。
        if (!isEditorContextFocused(project, editor)) { log.debug("evaluate skip: no focus"); return }
        val settings = CodeImeSettings.getInstance().state
        if (!settings.enabled) { log.debug("evaluate skip: disabled"); return }

        // EDT 上访问 PSI 需要 WriteIntentReadAction（2026.2 起强制断言；
        // 旧写法 ReadAction.compute 已弃用——1.5.1 的回归正是移除读锁导致）
        val context = com.intellij.openapi.application.WriteIntentReadAction.compute<EditorContext> {
            ContextAnalyzer.analyze(editor, project)
        }

        // PSI 滞后兜底：PSI 认为是代码，但光标所在行前面已经出现了 "//"，按注释处理
        val effective = if (context == EditorContext.CODE && lineBeforeCaretContainsLineComment(editor)) {
            EditorContext.COMMENT
        } else {
            context
        }

        var target = when (effective) {
            EditorContext.COMMENT -> ImeMode.CHINESE
            EditorContext.STRING -> if (settings.stringAsChinese) {
                ImeMode.CHINESE
            } else {
                // 字符一致性（跟随内容）：前字符是中文→保持中文接续；
                // 其余（引号/代码/字母旁）→ 切英文——字符串里多数时候写的是代码内容
                charConsistencyTarget(editor, fallback = ImeMode.ENGLISH, cjkOnly = false)
            }
            EditorContext.CODE -> {
                // 代码区默认英文，但若前一个字符是中文（刚打完中文接续），保持中文不打断
                charConsistencyTarget(editor, fallback = ImeMode.ENGLISH, cjkOnly = false)
            }
            EditorContext.UNKNOWN -> null
        }
        val state = stateFor(editor)
        val prevContext = state.lastContext
        // 语境跟踪必须始终更新：即使当前语境"不干预"（如默认的字符串）。
        state.lastContext = effective
        log.debug(
            "evaluate: offset=${editor.caretModel.offset} ctx=$context effective=$effective " +
                "target=${target ?: "NONE(不干预)"} prev=$prevContext " +
                "cooldown=${target != null && target == lastFailedTarget && System.currentTimeMillis() - lastFailedAt < FAILURE_COOLDOWN_MS}"
        )
        if (target == null) return

        // 拼音组字保护：用户正在按字母键（很可能在打拼音，IME 开启未上屏）。
        // 此时的光标事件可能来自组字窗口的插入，任何切换都会打断第一个字（1.5.6 实测）。
        // 静默直到组字结束（空格/回车上屏、Esc 取消、退格清空、或 6 秒无输入）。
        if (com.codeime.ime.KbdHookWatcher.isLetterTyping() && target == ImeMode.ENGLISH) {
            log.debug("skip: letter typing (likely pinyin composing)")
            return
        }

        // 用户手动覆盖：有效期内不切到与用户手动选择相反的模式——注释里也不例外
        // （用户在注释里主动切英文写标识符是正当需求，插件不得抢回中文）。
        // 语境级作用域：覆盖记录"发生时的语境"，语境变了（移出注释又移入等）自动恢复——
        // 避免 1.5.9 全局覆盖锁死一切切换的问题；字符串的误切已由 cjkOnly 收窄解决。
        // 窗口 30 秒。
        if (settings.respectManualToggles) {
            val o = userOverrideMode
            if (o != null && o != target &&
                System.currentTimeMillis() - userOverrideAt < USER_OVERRIDE_TTL_MS &&
                (userOverrideContext == null || userOverrideContext == effective)
            ) {
                log.debug("skip: user override mode=$o ctx=$effective")
                return
            }
        }

        // 失败冷却：短时间内不对同一目标反复尝试
        if (target == lastFailedTarget && System.currentTimeMillis() - lastFailedAt < FAILURE_COOLDOWN_MS) {
            return
        }

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
        private const val DEBOUNCE_MS = 20
        private const val FAILURE_COOLDOWN_MS = 1500

        /** 手动切换覆盖的有效期：期内同语境不强制切换 */
        private const val USER_OVERRIDE_TTL_MS = 30_000L
    }
}
