package com.fingertip.app.ui

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * 鼠标动作震动反馈：模拟机械微动的「嗒」，短促清脆。
 *
 * - 左键 16ms / 右键 24ms：右键略长，盲操作时不看屏幕也能区分左右键
 * - 幅度取高档位（200/220）：ERM 马达下短脉冲需要足够幅度才清晰可感
 * - minSdk 26，VibrationEffect.createOneShot 直接可用
 * - 无震动硬件 / 被系统策略拒绝时静默忽略，不影响点击本身
 */
object Haptics {

    /** 左键点击：16ms 短震。 */
    fun left(context: Context) = tick(context, 16, 200)

    /** 右键点击：24ms 短震（比左键略长，便于区分）。 */
    fun right(context: Context) = tick(context, 24, 220)

    /** 长按进入拖拽：30ms 默认幅度（触控板原有反馈，保持行为不变）。 */
    fun drag(context: Context) = tick(context, 30, VibrationEffect.DEFAULT_AMPLITUDE)

    private fun tick(context: Context, ms: Long, amplitude: Int) {
        try {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (!vibrator.hasVibrator()) return
            vibrator.vibrate(VibrationEffect.createOneShot(ms, amplitude))
        } catch (_: Exception) {
            // 震动失败不影响功能，静默忽略
        }
    }
}
