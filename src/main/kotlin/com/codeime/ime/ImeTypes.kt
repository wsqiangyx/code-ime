package com.codeime.ime

/** 目标输入模式 */
enum class ImeMode { CHINESE, ENGLISH }

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
