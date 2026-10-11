package com.codeime.settings

import com.codeime.ime.ImeMode
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import java.awt.event.InputEvent
import java.awt.event.KeyEvent

@Service(Service.Level.APP)
@State(name = "CodeImeSettings", storages = [Storage("CodeIme.xml")])
class CodeImeSettings : PersistentStateComponent<CodeImeSettings.State> {

    class State {
        /** 总开关 */
        var enabled: Boolean = true

        /** 字符串内是否视为中文 */
        var stringAsChinese: Boolean = false

        /** 优先用 IMM32 直接设置（失败自动降级为模拟热键） */
        var useDirectImmSet: Boolean = true

        /** 模拟的中英切换热键（KeyEvent.VK_*，0 表示禁用模拟） */
        var hotkeyCode: Int = KeyEvent.VK_SHIFT

        /** 热键修饰键（InputEvent.*_DOWN_MASK 组合） */
        var hotkeyModifiers: Int = 0

        /** IDE 失焦时：NONE / CHINESE / ENGLISH */
        var onDeactivate: String = "NONE"

        /** 切换失败时弹出提醒 */
        var showFailureNotifications: Boolean = true
    }

    @JvmField
    var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    /** IDE 失焦时的目标模式，null 表示不干预 */
    fun deactivateTarget(): ImeMode? = when (state.onDeactivate) {
        "CHINESE" -> ImeMode.CHINESE
        "ENGLISH" -> ImeMode.ENGLISH
        else -> null
    }

    fun hotkeyText(): String = hotkeyText(state.hotkeyCode, state.hotkeyModifiers)

    companion object {
        fun getInstance(): CodeImeSettings =
            ApplicationManager.getApplication().getService(CodeImeSettings::class.java)

        fun hotkeyText(keyCode: Int, modifiers: Int): String {
            if (keyCode == 0) return "（已禁用）"
            val parts = mutableListOf<String>()
            if (modifiers and InputEvent.CTRL_DOWN_MASK != 0) parts.add("Ctrl")
            if (modifiers and InputEvent.ALT_DOWN_MASK != 0) parts.add("Alt")
            if (modifiers and InputEvent.SHIFT_DOWN_MASK != 0) parts.add("Shift")
            if (modifiers and InputEvent.META_DOWN_MASK != 0) parts.add("Meta")
            parts.add(KeyEvent.getKeyText(keyCode))
            return parts.joinToString(" + ")
        }
    }
}
