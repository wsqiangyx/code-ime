package com.codeime.analyze

import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLiteralValue
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil

/** 光标所处的编辑器上下文 */
enum class EditorContext {
    /** 注释内 */
    COMMENT,

    /** 字符串字面量内 */
    STRING,

    /** 普通代码 */
    CODE,

    /** 无法判定（无 PSI 的编辑器，如控制台） */
    UNKNOWN,
}

/**
 * 基于 PSI 的上下文判定，对所有支持语言解析的文件类型通用。
 */
object ContextAnalyzer {

    fun analyze(editor: Editor, project: Project): EditorContext {
        val document = editor.document
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document)
            ?: return EditorContext.UNKNOWN

        // Git 提交信息编辑器：整体按注释对待（中文）
        val virtualFile = psiFile.virtualFile
        if (virtualFile != null && virtualFile.name == "COMMIT_EDITMSG") {
            return EditorContext.COMMENT
        }
        // IDE 内置 VCS 提交对话框（尽力识别：提交消息相关文件类型）
        val fileTypeName = psiFile.fileType.name
        if (fileTypeName.contains("Commit", ignoreCase = true)) {
            return EditorContext.COMMENT
        }

        val offset = editor.caretModel.offset.coerceIn(0, document.textLength)
        // 用逻辑位置取光标行：光标停在空白行时偏移量与下一行起点重合，
        // document.getLineNumber 会把光标行错算成下一行
        val caretLine = editor.caretModel.logicalPosition.line.coerceIn(0, document.lineCount - 1)
        var leaf = psiFile.findElementAt(offset)
        if (leaf == null && offset > 0) {
            // 光标紧跟在最后一个字符之后（例如刚敲完一个字符，或行尾）
            leaf = psiFile.findElementAt(offset - 1)
        }
        if (leaf == null) return EditorContext.UNKNOWN
        if (leaf is PsiWhiteSpace) {
            // 空白行本身不按注释处理（即使是注释的延续行）
            if (isLineBlank(document, caretLine)) return EditorContext.CODE
            // 回看：光标紧跟“同行”注释末尾（注释行行尾、块注释后的空格）。
            // 注意用空白段前一个字符所在行判断：空白段起始于行边界时，
            // getLineNumber 会把它归到下一行，导致 EOL 场景漏判。
            val runStart = leaf.textRange.startOffset
            if (runStart > 0 && document.getLineNumber(runStart - 1) == caretLine) {
                val prevLeaf = psiFile.findElementAt(runStart - 1)
                if (prevLeaf != null && prevLeaf !is PsiWhiteSpace && isInComment(prevLeaf)) {
                    return EditorContext.COMMENT
                }
            }
            // 前看：光标落在行首缩进或行内注释前的空隙时，看同行下一个非空白元素。
            // 典型场景：方向键从代码行移到缩进更深的注释行，光标停在缩进空白里。
            val nextLeaf = psiFile.findElementAt(leaf.textRange.endOffset)
            if (nextLeaf != null && nextLeaf !is PsiWhiteSpace &&
                document.getLineNumber(nextLeaf.textRange.startOffset) == caretLine
            ) {
                if (isInComment(nextLeaf)) return EditorContext.COMMENT
            }
            return EditorContext.CODE
        }

        if (isInComment(leaf)) return EditorContext.COMMENT
        if (isInString(leaf)) {
            // Python docstring（三引号字符串）语义上是注释/文档，按注释处理
            if (psiFile.language.id == "Python" && isTripleQuoted(leaf)) {
                return EditorContext.COMMENT
            }
            return EditorContext.STRING
        }
        return EditorContext.CODE
    }

    /** 元素或其近祖是否为三引号字符串（Python docstring 形态） */
    private fun isTripleQuoted(element: PsiElement): Boolean {
        var e: PsiElement? = element
        var depth = 0
        while (e != null && depth < 6) {
            val t = e.text
            if (t != null && t.length >= 3) {
                // 剥掉可能的字符串前缀（r/f/b/u 组合）再判断三引号
                val stripped = t.trimStart('r', 'R', 'b', 'B', 'f', 'F', 'u', 'U')
                if (stripped.startsWith("\"\"\"") || stripped.startsWith("'''")) {
                    return true
                }
            }
            e = e.parent
            depth++
        }
        return false
    }

    private fun isLineBlank(document: Document, line: Int): Boolean {
        val start = document.getLineStartOffset(line)
        val text = document.getText(TextRange(start, document.getLineEndOffset(line)))
        return text.isBlank()
    }

    private fun isInComment(element: PsiElement): Boolean {
        if (PsiTreeUtil.getParentOfType(element, PsiComment::class.java, false) != null) {
            return true
        }
        // 光标紧贴注释结尾（如块注释后的空白），元素本身不是注释但前一个兄弟是
        var prev: PsiElement? = element.prevSibling
        while (prev != null) {
            if (prev is PsiComment) return true
            if (prev is PsiWhiteSpace) {
                prev = prev.prevSibling
            } else {
                break
            }
        }
        return false
    }

    private fun isInString(element: PsiElement): Boolean {
        // Java 家族：PsiLiteralValue
        if (PsiTreeUtil.getParentOfType(element, PsiLiteralValue::class.java, false) != null) {
            return true
        }
        // Kotlin 等其他语言：按运行时类名嗅探，避免对具体语言插件产生编译依赖
        var e: PsiElement? = element
        var depth = 0
        while (e != null && depth < 64) {
            val name = e.javaClass.name
            if (name.contains("KtStringTemplate") || name.contains("KtLiteralStringTemplateEntry")) {
                return true
            }
            e = e.parent
            depth++
        }
        return false
    }
}
