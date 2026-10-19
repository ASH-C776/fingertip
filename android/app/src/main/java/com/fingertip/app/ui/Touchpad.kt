package com.fingertip.app.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.fingertip.app.net.WsClient
import kotlin.math.abs
import kotlin.math.roundToInt

/** 长按拖拽触发阈值：按下后静止 700ms 才进入拖拽，避免移动光标时误触发。 */
private const val LONG_PRESS_MS = 700L
/** 轻点判定窗口：小于该时长且未移动视为点击。 */
private const val TAP_MAX_MS = 400L
/** 静止判定区半径（px）：按下后手指在此范围内抖动不算移动，
 *  只有移出该范围才判定为滑动，保证长按能在手指微抖下稳定触发。 */
private const val DEADZONE_PX = 10f

/**
 * 触控板组件：把手机手势映射为电脑鼠标指令。
 *
 * 手势规则（《开发指南.md》3.2）：
 * - 单指滑动  -> mouse_move(dx, dy)
 * - 单指轻点  -> mouse_click left
 * - 双指轻点  -> mouse_click right
 * - 双指滑动  -> mouse_scroll（垂直滚动）
 * - 长按拖拽  -> mouse_down / 移动 / mouse_up
 */
@Composable
fun Touchpad(
    enabled: Boolean,
    ws: WsClient,
    slideRatio: Float = 1.0f,
    scrollSpeed: Int = 5,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var pressPos by remember { mutableStateOf<Offset?>(null) }
    // 内层圆角必须与 MainScreen 触控板外框 Surface（18dp）一致；
    // 若大于外层会被裁切，出现尖角
    val shape = RoundedCornerShape(18.dp)
    val background = if (enabled) {
        Brush.verticalGradient(
            listOf(Color(0x33FFFFFF), Color(0x1AFFFFFF)),
        )
    } else {
        Brush.verticalGradient(listOf(Color(0x0D000000), Color(0x08000000)))
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(background)
            .semantics { contentDescription = "触控板" }
            .pointerInput(enabled, ws) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    handleGesture(ws, slideRatio, scrollSpeed, context) { pressPos = it }
                }
            }
    ) {
        // 手指位置光点反馈：Canvas 不参与指针事件，不影响手势识别
        Canvas(modifier = Modifier.fillMaxSize()) {
            pressPos?.let {
                drawCircle(
                    color = Color.White.copy(alpha = 0.25f),
                    radius = 36.dp.toPx(),
                    center = it,
                )
            }
        }
    }
}

/** 单个手势（从按下到全部抬起）的完整处理。
 *
 * 状态机核心：
 * - 移动光标：手指一旦滑动，移动即重置「静止计时」，持续滑动永不触发拖拽
 * - 长按拖拽：仅当「静止时长（now - 最近一次移动时间）」超过 LONG_PRESS_MS 才进入
 * - 点击：未发生明显移动且总时长 < TAP_MAX_MS
 * - 双指：滑动=滚轮，轻点=右键
 */
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.handleGesture(
    ws: WsClient,
    slideRatio: Float,
    scrollSpeed: Int,
    context: Context,
    onPress: (Offset?) -> Unit,
) {
    val down = awaitFirstDown(requireUnconsumed = false)
    down.consume()
    onPress(centroidOf(down))

    val startTime = System.currentTimeMillis()
    val startPos = centroidOf(down)  // 按下位置（静止判定区中心）
    var pointerCount = 1
    var lastCentroid = centroidOf(down)
    var moved = false          // 是否发生过明显位移（区分点击 vs 滑动）
    var activeMove = false     // 是否已移出静止判定区（真正开始滑动）
    var dragStarted = false    // 是否进入长按拖拽模式（静止超时）
    var dragSent = false       // mouse_down 是否已发送
    var hapticSent = false     // 长按震动反馈是否已发
    var lastSendTime = 0L      // 移动/滚轮节流
    var lastMoveTime = startTime  // 最近一次明显移动的时间（静止计时基准）
    var verticalAccum = 0f     // 双指滚轮累积

    while (true) {
        val event = awaitPointerEvent()
        val pressedChanges = event.changes.filter { it.pressed }
        val currentCount = pressedChanges.size

        if (currentCount != pointerCount) {
            // 指针数量变化：双指落下/抬起一指，重置基准点
            pointerCount = currentCount
            if (pointerCount > 0) {
                lastCentroid = centroidOf(pressedChanges)
                verticalAccum = 0f
            }
        } else if (pointerCount > 0) {
            val newCentroid = centroidOf(pressedChanges)
            val dx = newCentroid.x - lastCentroid.x
            val dy = newCentroid.y - lastCentroid.y
            val now = System.currentTimeMillis()

            if (pointerCount == 1) {
                if (!activeMove) {
                    // 仍在静止判定区：手指微抖不算移动，只有移出判定区才进入滑动
                    val distFromStart = (newCentroid - startPos).getDistance()
                    if (distFromStart > DEADZONE_PX) {
                        activeMove = true
                        moved = true
                        lastMoveTime = now
                        if (now - lastSendTime > 8) {
                            ws.send(
                                "mouse_move",
                                mapOf("dx" to (dx * slideRatio).roundToInt(), "dy" to (dy * slideRatio).roundToInt())
                            )
                            lastSendTime = now
                        }
                    }
                } else {
                    // 已进入真实滑动：持续发送移动（普通移动与拖拽共用）
                    if (abs(dx) > 0.5f || abs(dy) > 0.5f) {
                        moved = true
                        lastMoveTime = now
                        if (now - lastSendTime > 8) { // 节流：约 120Hz
                            ws.send(
                                "mouse_move",
                                mapOf("dx" to (dx * slideRatio).roundToInt(), "dy" to (dy * slideRatio).roundToInt())
                            )
                            lastSendTime = now
                        }
                    }
                }

                // 长按判定：仅在未移出静止判定区时计时（持续滑动永不触发）
                if (!dragStarted && !activeMove && now - startTime > LONG_PRESS_MS) {
                    dragStarted = true
                }
                if (dragStarted) {
                    if (!dragSent) {
                        ws.send("mouse_down", mapOf("button" to "left"))
                        dragSent = true
                    }
                    if (!hapticSent) {
                        hapticSent = true
                        Haptics.drag(context)
                    }
                }
            } else if (pointerCount >= 2) {
                // 双指滑动 -> 滚轮（主垂直，兼顾水平）
                if (abs(dy) > 0.5f || abs(dx) > 0.5f) {
                    moved = true
                    verticalAccum += dy
                    if (abs(verticalAccum) >= 2f && now - lastSendTime > 16) {
                        ws.send("mouse_scroll", mapOf("delta" to (verticalAccum * scrollSpeed / 4f).roundToInt()))
                        verticalAccum = 0f
                        lastSendTime = now
                    }
                }
            }
            lastCentroid = newCentroid
            onPress(newCentroid)
        }

        // 手指全部抬起 -> 手势结束
        if (event.changes.none { it.pressed }) break
    }

    onPress(null)
    val duration = System.currentTimeMillis() - startTime

    when {
        dragSent -> {
            // 拖拽结束，释放左键
            ws.send("mouse_up", mapOf("button" to "left"))
        }
        !moved && duration < TAP_MAX_MS -> {
            // 轻点：单指=左键，双指=右键（先震后发，网络延迟不影响手感）
            if (pointerCount >= 2) {
                Haptics.right(context)
                ws.send("mouse_click", mapOf("button" to "right"))
            } else {
                Haptics.left(context)
                ws.send("mouse_click", mapOf("button" to "left"))
            }
        }
    }
}

private fun centroidOf(changes: List<PointerInputChange>): Offset {
    var x = 0f; var y = 0f
    for (c in changes) { x += c.position.x; y += c.position.y }
    return Offset(x / changes.size, y / changes.size)
}

private fun centroidOf(change: PointerInputChange): Offset = change.position
