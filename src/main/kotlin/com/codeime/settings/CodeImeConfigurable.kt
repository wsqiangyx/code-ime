package com.codeime.settings

import com.intellij.openapi.options.Configurable
import java.awt.event.InputEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.BoxLayout
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField

/**
 * 设置界面：Settings → Tools → CodeIME 输入法自动切换
 */
class CodeImeConfigurable : Configurable {

    private var enabledBox: JCheckBox? = null
    private var stringBox: JCheckBox? = null
    private var directBox: JCheckBox? = null
    private var hotkeyField: JTextField? = null
    private var deactivateCombo: JComboBox<String>? = null
    private var notifyBox: JCheckBox? = null

    // 热键捕获的暂存值
    private var capturedKeyCode: Int = KeyEvent.VK_SHIFT
    private var capturedModifiers: Int = 0

    override fun getDisplayName(): String = "CodeIME 输入法自动切换"

    override fun createComponent(): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)

        enabledBox = JCheckBox("启用自动切换（注释中文 / 代码英文）")
        stringBox = JCheckBox("字符串内视为中文（默认不干预）")
        directBox = JCheckBox("优先直接设置输入法状态（推荐，失败时自动降级为模拟热键）")
        notifyBox = JCheckBox("切换失败时弹出提醒")

        hotkeyField = JTextField(14).apply {
            isEditable = false
            isFocusable = true
            addKeyListener(object : KeyAdapter() {
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
                    text = CodeImeSettings.hotkeyText(capturedKeyCode, capturedModifiers)
                }
            })
        }

        deactivateCombo = JComboBox(arrayOf("不干预", "切换到中文", "切换到英文"))

        val hotkeyRow = JPanel().apply {
            add(JLabel("中英切换模拟热键（与系统输入法设置保持一致，点击输入框后按下组合键，Esc 还原）："))
            add(hotkeyField)
        }

        panel.add(enabledBox)
        panel.add(stringBox)
        panel.add(directBox)
        panel.add(hotkeyRow)
        panel.add(JLabel("IDE 失去焦点时："))
        panel.add(deactivateCombo)
        panel.add(notifyBox)
        panel.add(JLabel("提示：模拟热键需与你系统中切换中/英的热键一致（微软拼音默认为 Shift）。"))

        reset()
        return panel
    }

    override fun isModified(): Boolean {
        val state = CodeImeSettings.getInstance().state
        return enabledBox?.isSelected != state.enabled ||
            stringBox?.isSelected != state.stringAsChinese ||
            directBox?.isSelected != state.useDirectImmSet ||
            notifyBox?.isSelected != state.showFailureNotifications ||
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
        deactivateCombo?.selectedIndex = deactivateIndex(state.onDeactivate)
    }

    override fun disposeUIResources() {
        enabledBox = null
        stringBox = null
        directBox = null
        hotkeyField = null
        deactivateCombo = null
        notifyBox = null
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
