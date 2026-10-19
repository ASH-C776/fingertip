package com.fingertip.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fingertip.app.net.WsClient

/** 发现的电脑设备（阶段 6）。 */
data class DiscoveredDevice(val name: String, val ip: String, val port: Int)

/**
 * 阶段 3：主界面。
 * 顶部状态栏 + 功能按钮排 + 触控板 + 底部按键区。
 */
@Composable
fun MainScreen(
    connection: WsClient.ConnectionState,
    deviceName: String,
    ws: WsClient,
    slideRatio: Float = 2.5f,
    scrollSpeed: Int = 5,
    onOpenSettings: () -> Unit,
    onOpenKeyboard: () -> Unit,
    onDiscover: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val connected = connection is WsClient.ConnectionState.Connected

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(10.dp))

        // 顶部状态栏（未连接时显示「发现设备」）
        StatusBar(connection, deviceName, onOpenSettings, onDiscover)

        Spacer(Modifier.height(14.dp))

        // 功能按钮排
        FunctionButtons(connected, ws)

        Spacer(Modifier.height(14.dp))

        // 触控板（视觉中心，卡片框）
        Box(modifier = Modifier.weight(1f)) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp, MaterialTheme.colorScheme.outlineVariant
                ),
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Touchpad(
                        enabled = connected,
                        ws = ws,
                        slideRatio = slideRatio,
                        scrollSpeed = scrollSpeed,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (!connected) {
                        // 未连接提示（框内居中，主副两级）
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                "未连接",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "点击上方「发现设备」连接电脑",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // 底部按键区
        BottomButtons(connected, ws, onOpenKeyboard)

        Spacer(Modifier.height(12.dp))
    }
}

/** 设备发现弹窗：列出局域网内发现的电脑，点击即可连接。 */
@Composable
fun DiscoverDialog(
    searching: Boolean,
    devices: List<DiscoveredDevice>,
    onSelect: (DiscoveredDevice) -> Unit,
    onRescan: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = { Text("选择电脑") },
        text = {
            Column {
                if (searching) {
                    Text(
                        "正在搜索附近的电脑…",
                        color = MaterialTheme.colorScheme.secondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else if (devices.isEmpty()) {
                    Text(
                        "未发现设备。\n请确认电脑端「指间」已启动，且手机与电脑在同一 WiFi。",
                        color = MaterialTheme.colorScheme.secondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    devices.forEach { d ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(d) }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text(
                                    d.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    "${d.ip}:${d.port}",
                                    color = MaterialTheme.colorScheme.secondary,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onRescan, enabled = !searching) { Text("重新搜索") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

@Composable
private fun StatusBar(
    connection: WsClient.ConnectionState,
    deviceName: String,
    onOpenSettings: () -> Unit,
    onDiscover: () -> Unit,
) {
    val targetDotColor = when (connection) {
        is WsClient.ConnectionState.Connected -> MaterialTheme.colorScheme.tertiary
        is WsClient.ConnectionState.Connecting -> Color(0xFFFF9500)
        is WsClient.ConnectionState.Disconnected -> Color(0xFF8E8E93)
    }
    val dotColor by animateColorAsState(targetDotColor, tween(300))
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (connection is WsClient.ConnectionState.Disconnected) {
            // 未连接：状态栏直接提供「发现设备」入口
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(dotColor, CircleShape)
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onDiscover) {
                Text(
                    "发现设备",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Spacer(Modifier.weight(1f))
        } else {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(dotColor, CircleShape)
            )
            Spacer(Modifier.width(8.dp))
            when (connection) {
                is WsClient.ConnectionState.Connected -> Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "已连接",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        deviceName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                else -> Text(
                    "连接中…",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Default.Settings, contentDescription = "设置", tint = MaterialTheme.colorScheme.secondary)
        }
    }
}

@Composable
private fun FunctionButtons(connected: Boolean, ws: WsClient) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FuncButton("复制", connected, Modifier.weight(1f)) { ws.send("key", mapOf("key" to "copy")) }
        FuncButton("粘贴", connected, Modifier.weight(1f)) { ws.send("key", mapOf("key" to "paste")) }
        FuncButton("回车", connected, Modifier.weight(1f)) { ws.send("key", mapOf("key" to "enter")) }
        FuncButton("退格", connected, Modifier.weight(1f)) { ws.send("key", mapOf("key" to "backspace")) }
    }
}

@Composable
private fun FuncButton(
    text: String,
    connected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, tween(100))
    Button(
        onClick = onClick,
        enabled = connected,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .height(48.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .semantics { contentDescription = text },
        interactionSource = interactionSource,
        // Material3 Button 默认水平内边距 24dp，四等分小按钮里两个字只剩 ~18dp，
        // 文字会被 Surface 的 shape 裁掉（「复制粘贴显示不全」）。收紧内边距。
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 1.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun BottomButtons(connected: Boolean, ws: WsClient, onOpenKeyboard: () -> Unit) {
    val context = LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        // 左键
        val leftInteraction = remember { MutableInteractionSource() }
        val leftPressed by leftInteraction.collectIsPressedAsState()
        val leftScale by animateFloatAsState(if (leftPressed) 0.96f else 1f, tween(100))
        Button(
            onClick = {
                Haptics.left(context)
                ws.send("mouse_click", mapOf("button" to "left"))
            },
            enabled = connected,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .weight(1f)
                .height(54.dp)
                .graphicsLayer { scaleX = leftScale; scaleY = leftScale }
                .semantics { contentDescription = "左键" },
            interactionSource = leftInteraction,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            ),
        ) {
            Text(
                "左键",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 键盘（居中突出）
        val kbInteraction = remember { MutableInteractionSource() }
        val kbPressed by kbInteraction.collectIsPressedAsState()
        val kbScale by animateFloatAsState(if (kbPressed) 0.96f else 1f, tween(100))
        Button(
            onClick = onOpenKeyboard,
            enabled = connected,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .weight(1.6f)
                .height(54.dp)
                .graphicsLayer { scaleX = kbScale; scaleY = kbScale }
                .semantics { contentDescription = "键盘" },
            interactionSource = kbInteraction,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.5f),
            ),
        ) {
            Text(
                "键盘",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 右键
        val rightInteraction = remember { MutableInteractionSource() }
        val rightPressed by rightInteraction.collectIsPressedAsState()
        val rightScale by animateFloatAsState(if (rightPressed) 0.96f else 1f, tween(100))
        Button(
            onClick = {
                Haptics.right(context)
                ws.send("mouse_click", mapOf("button" to "right"))
            },
            enabled = connected,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .weight(1f)
                .height(54.dp)
                .graphicsLayer { scaleX = rightScale; scaleY = rightScale }
                .semantics { contentDescription = "右键" },
            interactionSource = rightInteraction,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            ),
        ) {
            Text(
                "右键",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
