package com.codeime.listener

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.application.ApplicationManager

/**
 * 项目级监听：编辑器标签页切换时光标没有移动事件，
 * 需要主动按新编辑器的光标位置重新判定一次输入法。
 */
class EditorTabWatcher : FileEditorManagerListener {

    override fun selectionChanged(event: FileEditorManagerEvent) {
        val newEditor: FileEditor = event.newEditor ?: return
        val textEditor = newEditor as? TextEditor ?: return
        ApplicationManager.getApplication().invokeLater {
            ApplicationManager.getApplication()
                ?.getService(EditorCaretWatcher::class.java)
                ?.resyncEditor(textEditor.editor)
        }
    }
}
