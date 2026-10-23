package com.codeime.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.dsl.builder.panel
import java.awt.event.InputEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JTextField

/**
 * 设置界面：Settings → Tools → CodeIME 输入法自动切换
 * 使用 IntelliJ UI DSL 构建，保证与 IDE 设置页一致的布局风格。
 */
class CodeImeConfigurable : Configurable {

    private var enabledBox: JCheckBox? = null
    private var stringBox: JCheckBox? = null
    private var directBox: JCheckBox? = null
    private var hotkeyField: JTextField? = null
    private var deactivateCombo: JComboBox<String>? = null
    private var notifyBox: JCheckBox? = null
    private var followUserBox: JCheckBox? = null

    // 热键捕获的暂存值
    private var capturedKeyCode: Int = KeyEvent.VK_SHIFT
    private var capturedModifiers: Int = 0

    override fun getDisplayName(): String = "CodeIME 输入法自动切换"

    override fun createComponent(): JComponent {
        val component = panel {
            group("自动切换") {
                row {
                    checkBox("启用自动切换（注释中文 / 代码英文）").also { enabledBox = it.component }
                }
                row {
                    checkBox("字符串内视为中文（默认不干预）").also { stringBox = it.component }
                }
                row {
                    checkBox("优先直接设置输入法状态（关闭后优先模拟热键；微软拼音建议关闭）")
                        .also { directBox = it.component }
                }
                row {
                    checkBox("切换失败时弹出提醒").also { notifyBox = it.component }
                }
                row {
                    checkBox("优先跟随手动切换（手动按 Shift 后 60 秒内，同语境不强制切换）")
                        .also { followUserBox = it.component }
                }
            }
            group("模拟热键") {
                row("中英切换热键：") {
                    textField().apply {
                        component.isEditable = false
                        component.addKeyListener(hotkeyCaptureListener())
                        hotkeyField = component
                    }
                }.comment(
                    "需与系统输入法的中/英切换热键保持一致（微软拼音默认 Shift）。" +
                        "点击输入框后按下组合键即可录制，Esc 还原。"
                )
                row("IDE 失去焦点时：") {
                    comboBox(listOf("不干预", "切换到中文", "切换到英文")).also { deactivateCombo = it.component }
                }
            }
        }
        reset()
        return component
    }

    override fun isModified(): Boolean {
        val state = CodeImeSettings.getInstance().state
        return enabledBox?.isSelected != state.enabled ||
            stringBox?.isSelected != state.stringAsChinese ||
            directBox?.isSelected != state.useDirectImmSet ||
            notifyBox?.isSelected != state.showFailureNotifications ||
            followUserBox?.isSelected != state.respectManualToggles ||
            capturedKeyCode != state.hotkeyCode ||
            capturedModifiers != state.hotkeyModifiers ||
            deactivateCombo?.selectedIndex != deactivateIndex(state.onDeactivate)
    }

    override fun apply() {
        val s = CodeImeSettings.getInstance().state
        s.enabled = enabledBox?.isSelected ?: s.enabled
        s.stringAsChinese = stringBox?.isSelected ?: s.stringAsChinese
        s.useDirectImmSet = directBox?.isSelected ?: s.useDirectImmSet
        s.showFailureNotifications = notifyBox?.isSelected ?: s.showFailureNotifications
        s.respectManualToggles = followUserBox?.isSelected ?: s.respectManualToggles
        s.hotkeyCode = capturedKeyCode
        s.hotkeyModifiers = capturedModifiers
        s.onDeactivate = when (deactivateCombo?.selectedIndex) {
            1 -> "CHINESE"
            2 -> "ENGLISH"
            else -> "NONE"
        }
    }

    override fun reset() {
        restoreCapturedHotkey()
        val state = CodeImeSettings.getInstance().state
        enabledBox?.isSelected = state.enabled
        stringBox?.isSelected = state.stringAsChinese
        directBox?.isSelected = state.useDirectImmSet
        notifyBox?.isSelected = state.showFailureNotifications
        followUserBox?.isSelected = state.respectManualToggles
        deactivateCombo?.selectedIndex = deactivateIndex(state.onDeactivate)
    }

    override fun disposeUIResources() {
        enabledBox = null
        stringBox = null
        directBox = null
        hotkeyField = null
        deactivateCombo = null
        notifyBox = null
        followUserBox = null
    }

    private fun hotkeyCaptureListener(): KeyAdapter = object : KeyAdapter() {
        override fun keyPressed(e: KeyEvent) {
            e.consume()
            val code = e.keyCode
            if (code == KeyEvent.VK_ESCAPE) {
                restoreCapturedHotkey()
                return
            }
            if (code == KeyEvent.VK_SHIFT || code == KeyEvent.VK_CONTROL ||
                code == KeyEvent.VK_ALT || code == KeyEvent.VK_META
            ) {
                capturedKeyCode = code
                capturedModifiers = 0
            } else if (code != KeyEvent.VK_TAB && code != KeyEvent.VK_ENTER) {
                capturedKeyCode = code
                capturedModifiers = e.modifiersEx and (
                    InputEvent.CTRL_DOWN_MASK or InputEvent.ALT_DOWN_MASK or
                        InputEvent.SHIFT_DOWN_MASK or InputEvent.META_DOWN_MASK
                    )
            } else {
                return
            }
            hotkeyField?.text = CodeImeSettings.hotkeyText(capturedKeyCode, capturedModifiers)
        }
    }

    private fun restoreCapturedHotkey() {
        val state = CodeImeSettings.getInstance().state
        capturedKeyCode = state.hotkeyCode
        capturedModifiers = state.hotkeyModifiers
        hotkeyField?.text = CodeImeSettings.hotkeyText(capturedKeyCode, capturedModifiers)
    }

    private fun deactivateIndex(value: String): Int = when (value) {
        "CHINESE" -> 1
        "ENGLISH" -> 2
        else -> 0
    }
}
