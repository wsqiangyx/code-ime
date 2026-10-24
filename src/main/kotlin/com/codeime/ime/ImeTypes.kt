package com.codeime.ime

/** 目标输入模式 */
enum class ImeMode { CHINESE, ENGLISH;
    companion object {
        private fun isCjkChar(c: Char): Boolean {
            val v = c.code
            return (v in 0x3000..0x303F) ||   // CJK 标点（。，、）
                (v in 0x4E00..0x9FFF) ||       // CJK 统一表意文字
                (v in 0x3400..0x4DBF) ||       // 扩展 A
                (v in 0xF900..0xFAFF) ||       // 兼容表意文字
                (v in 0xFF00..0xFFEF)          // 全角形式（！：（）等）
        }

        /**
         * 按前一个字符推断输入模式（用于"不干预"语境的字符一致性）：
         * CJK/中文标点 → 中文；ASCII 字母 → 英文；数字/空格/符号 → null（保持现状）
         */
        fun fromChar(c: Char): ImeMode? = when {
            isCjkChar(c) -> CHINESE
            c.isLetter() && c.code < 0x80 -> ENGLISH
            else -> null
        }
    }
}

/** 一次切换动作的结果 */
enum class SwitchResult {
    /** 已处于目标状态，无需动作 */
    NOOP,
    /** 通过 IMM32 直接设置成功 */
    DIRECT,
    /** 模拟切换热键成功 */
    HOTKEY,
    /** 切换失败 */
    FAILED,
}

/** 输入法控制器抽象，便于后续扩展 macOS 等平台 */
interface InputMethodController {
    fun name(): String
    fun isAvailable(): Boolean
    fun currentMode(): ImeMode?
    fun switchTo(target: ImeMode): SwitchResult

    /** 插件最近一次注入切换按键/直接设置的时间戳；用于把模式变化归因给插件还是用户 */
    fun lastPluginSwitchAt(): Long = 0L
}

object InputMethodManager {
    val instance: InputMethodController by lazy {
            if (com.intellij.openapi.util.SystemInfo.isWindows) {
            WindowsInputMethod()
        } else {
            UnsupportedInputMethod()
        }
    }
}

private class UnsupportedInputMethod : InputMethodController {
    override fun name() = "Unsupported"
    override fun isAvailable() = false
    override fun currentMode(): ImeMode? = null
    override fun switchTo(target: ImeMode) = SwitchResult.FAILED
}
