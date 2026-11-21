package com.codeime.compat;

import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;

/**
 * FileEditorManager.getInstance 的直接静态调用包装。
 *
 * 2026.3 平台将 FileEditorManager Kotlin 化（新增 Companion 对象）。从 Kotlin
 * 源码调用 {@code FileEditorManager.getInstance(...)} 会编译成 Companion 引用，
 * 而 2026.1/2026.2 上该类仍是纯 Java（无 Companion 内部类）→ 插件验证报
 * "Missing: FileEditorManager$Companion"（Critical）。
 *
 * 从 Java 调用保证生成 invokestatic 直接引用——261/262 的 Java 静态与 263 的
 * @JvmStatic 门面三方都存在，全版本兼容。
 */
public final class FileEditorManagers {
    private FileEditorManagers() {
    }

    public static FileEditorManager getInstance(Project project) {
        return FileEditorManager.getInstance(project);
    }
}
