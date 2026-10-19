package com.fingertip.app.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import com.fingertip.app.BuildConfig
import com.fingertip.app.R
import com.fingertip.app.net.WsClient
import kotlin.math.roundToInt

/** 设置页路由。 */
enum class SettingPage { MAIN, CONNECTION, MOUSE, HELP }

/** 主题模式：老板黑 / 脸色白 / 淤青紫（用户命名）。 */
enum class ThemeMode(val label: String) {
    BOSS_BLACK("老板黑"),
    FACE_WHITE("脸色白"),
    BRUISE_PURPLE("淤青紫"),
}

/**
 * 设置页（一级菜单 + 二级页面）。
 * 阶段 3：连接 / 关于；阶段 7：主题 / 鼠标参数 + 持久化。
 */
@Composable
fun SettingsScreen(
    page: SettingPage,
    connection: WsClient.ConnectionState,
    deviceName: String,
    ws: WsClient,
    host: String,
    port: String,
    themeMode: ThemeMode,
    slideRatio: Float,
    sensitivity: Float,
    scrollSpeed: Float,
    syncIntervalMs: Int,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onThemeChange: (ThemeMode) -> Unit,
    onSlideRatioChange: (Float) -> Unit,
    onSensitivityChange: (Float) -> Unit,
    onScrollSpeedChange: (Float) -> Unit,
    onSyncIntervalChange: (Int) -> Unit,
    onNavigate: (SettingPage) -> Unit,
    onBack: () -> Unit,
    onDiscover: () -> Unit,
) {
    // 设置子页切换：淡入淡出，避免硬切
    Crossfade(targetState = page, label = "settingPage", animationSpec = tween(200)) { target ->
        when (target) {
            SettingPage.MAIN -> SettingsMain(
            connection = connection,
            deviceName = deviceName,
            themeMode = themeMode,
            onThemeChange = onThemeChange,
            onNavigate = onNavigate,
        )
        SettingPage.CONNECTION -> ConnectionPage(
            connection = connection,
            deviceName = deviceName,
            ws = ws,
            host = host,
            port = port,
            onHostChange = onHostChange,
            onPortChange = onPortChange,
            onConnect = onConnect,
            onDisconnect = onDisconnect,
            onBack = onBack,
            onDiscover = onDiscover,
        )
        SettingPage.HELP -> HelpPage(onBack = onBack)
        SettingPage.MOUSE -> MousePage(
            slideRatio = slideRatio,
            sensitivity = sensitivity,
            scrollSpeed = scrollSpeed,
            syncIntervalMs = syncIntervalMs,
            onSlideRatioChange = onSlideRatioChange,
            onSensitivityChange = onSensitivityChange,
            onScrollSpeedChange = onScrollSpeedChange,
            onSyncIntervalChange = onSyncIntervalChange,
            onBack = onBack,
            )
        }
    }
}

@Composable
private fun SettingsMain(
    connection: WsClient.ConnectionState,
    deviceName: String,
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    onNavigate: (SettingPage) -> Unit,
) {
    val connText = when (connection) {
        is WsClient.ConnectionState.Connected -> "已连接 $deviceName"
        is WsClient.ConnectionState.Connecting -> "连接中…"
        is WsClient.ConnectionState.Disconnected -> "未连接"
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        SettingHeader("设置", onBack = null)
        // 分组卡片：连接 / 主题 / 鼠标 / 使用说明 + 关于铺开（圆角 18 容器 + 内部细分隔线）
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column {
                SettingItem("连接", right = connText) { onNavigate(SettingPage.CONNECTION) }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                // 主题：三个横排按钮直接切换（无二级页）
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text("主题", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeMode.entries.forEach { mode ->
                            ThemeButton(
                                label = mode.label,
                                selected = mode == themeMode,
                                onClick = { onThemeChange(mode) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                SettingItem("鼠标") { onNavigate(SettingPage.MOUSE) }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                SettingItem("使用说明") { onNavigate(SettingPage.HELP) }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                // 关于：按用户要求从二级页铺开为一级内容，放在「使用说明」下一行
                AboutInline()
            }
        }
    }
}

/** 主题横排按钮：选中态用主题主色填充（圆角 12）。 */
@Composable
private fun ThemeButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(12.dp),
        // 三分屏小按钮：默认 24dp 水平内边距会裁掉「老板黑」三字，收紧
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 阶段 7：鼠标参数（滑动比例 / 灵敏度 / 滚动速度 / 上屏间隔）。 */
@Composable
private fun MousePage(
    slideRatio: Float,
    sensitivity: Float,
    scrollSpeed: Float,
    syncIntervalMs: Int,
    onSlideRatioChange: (Float) -> Unit,
    onSensitivityChange: (Float) -> Unit,
    onScrollSpeedChange: (Float) -> Unit,
    onSyncIntervalChange: (Int) -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        SettingHeader("鼠标", onBack)
        Spacer(Modifier.height(12.dp))

        // 档位化调节（steps 吸附 + 刻度点），步进分别为 0.5 / 0.25 / 1 / 100ms
        SliderItem(
            title = "滑动比例",
            valueText = "%.1f".format(slideRatio),
            value = slideRatio,
            range = 1.0f..4.0f,
            steps = 5,
            onChange = onSlideRatioChange,
        )
        SliderItem(
            title = "灵敏度",
            valueText = "%.2f".format(sensitivity),
            value = sensitivity,
            range = 0.5f..2.0f,
            steps = 5,
            onChange = onSensitivityChange,
        )
        SliderItem(
            title = "滚动速度",
            valueText = "%.0f".format(scrollSpeed),
            value = scrollSpeed,
            range = 1f..15f,
            steps = 13,
            onChange = onScrollSpeedChange,
        )
        SliderItem(
            title = "上屏间隔",
            valueText = "${syncIntervalMs} 毫秒",
            value = syncIntervalMs.toFloat(),
            range = 100f..800f,
            steps = 6,
            onChange = { onSyncIntervalChange(it.roundToInt()) },
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "参数实时生效并自动保存，重启后保留",
            color = MaterialTheme.colorScheme.secondary,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SliderItem(
    title: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.weight(1f))
            Text(valueText, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodyMedium)
        }
        val colors = SliderDefaults.colors(
            thumbColor = Color.White,
            activeTrackColor = MaterialTheme.colorScheme.primary,
            inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant,
        )
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            interactionSource = interactionSource,
            thumb = {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .graphicsLayer {
                            scaleX = if (pressed) 1.18f else 1f
                            scaleY = if (pressed) 1.18f else 1f
                        }
                        .shadow(2.dp, CircleShape)
                        .background(Color.White, CircleShape)
                )
            },
            colors = colors,
        )
        // 档位刻度点：steps+2 个停靠点均分排布，当前档高亮——让「一级一级」看得见。
        // 轨道本体用 M3 默认实现（自定义 track 有 thumb 半径对齐坑），只加刻度行。
        val stepSize = (range.endInclusive - range.start) / (steps + 1)
        val currentStep = ((value - range.start) / stepSize).roundToInt()
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            repeat(steps + 2) { i ->
                Box(
                    Modifier
                        .size(3.dp)
                        .background(
                            if (i == currentStep) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                            CircleShape,
                        )
                )
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun ConnectionPage(
    connection: WsClient.ConnectionState,
    deviceName: String,
    ws: WsClient,
    host: String,
    port: String,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onBack: () -> Unit,
    onDiscover: () -> Unit,
) {
    val connected = connection is WsClient.ConnectionState.Connected
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
    ) {
        SettingHeader("连接", onBack)

        if (connected) {
            // 已连接状态
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
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
            Text(
                "$host:$port",
                color = MaterialTheme.colorScheme.secondary,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onDisconnect,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("断开", style = MaterialTheme.typography.bodyLarge)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "已记住此设备，下次打开自动连接",
                color = MaterialTheme.colorScheme.secondary,
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Spacer(Modifier.height(16.dp))
            Text(
                "● 未连接",
                color = MaterialTheme.colorScheme.secondary,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(24.dp))

            Text("发现", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onDiscover,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("搜索附近设备", style = MaterialTheme.typography.bodyLarge)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "自动发现已开启，点击上方按钮搜索同一 WiFi 下的电脑",
                color = MaterialTheme.colorScheme.secondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 20.dp),
            )

            Spacer(Modifier.height(24.dp))
            Text("手动连接", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 20.dp),
            ) {
                OutlinedTextField(
                    value = host,
                    onValueChange = onHostChange,
                    label = { Text("IP 地址") },
                    placeholder = { Text("192.168.1.100") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                    ),
                    modifier = Modifier.weight(3f),
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = onPortChange,
                    label = { Text("端口") },
                    placeholder = { Text("8765") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                    ),
                    modifier = Modifier.weight(2f),
                )
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onConnect,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("连接", fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * 关于信息（一级菜单内联铺开，不再进二级页）：
 * 放在设置列表「使用说明」下一行，版本号取 BuildConfig 实时值（避免写死过期）。
 */
@Composable
private fun AboutInline() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("指间", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "Fingertip",
            color = MaterialTheme.colorScheme.secondary,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "版本 ${BuildConfig.VERSION_NAME}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "作者：基耶杰",
            color = MaterialTheme.colorScheme.secondary,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** 使用说明：连接、手势、键盘上屏、设置项效果（可滚动）。 */
@Composable
private fun HelpPage(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        SettingHeader("使用说明", onBack)
        Spacer(Modifier.height(12.dp))

        HelpSection("一、连接电脑") {
            "1. 电脑端先运行「指间」服务，手机与电脑需连接同一 WiFi\n" +
            "2. 打开 App，点击首页顶部的「发现设备」\n" +
            "3. 在设备列表中选择你的电脑，即可连接\n" +
            "4. 电脑端显示「已连接」即配对成功"
        }
        HelpSection("二、触控板手势") {
            "· 单指轻点：左键单击\n" +
            "· 单指滑动：移动鼠标\n" +
            "· 双指滑动：滚动页面（相当于滚轮）\n" +
            "· 长按约 0.7 秒（手机震动一下）：进入拖拽模式，移动手指即可拖动窗口、文件，松手结束\n" +
            "· 提示：长按后请等震动反馈再移动，避免手指微动被误判成滑动"
        }
        HelpSection("三、键盘与语音输入") {
            "· 打字或语音输入的内容会自动上屏到电脑当前光标处\n" +
            "· 上屏间隔可在「鼠标」设置中调整，调小则上屏更快（最终会自动对齐）"
        }
        HelpSection("四、设置项说明") {
            "· 鼠标滑动比例（1~4）：手指滑动换算为鼠标移动的倍数，越大鼠标跑得越远\n" +
            "· 灵敏度（0.5~2）：整体操作灵敏度，越高反应越快\n" +
            "· 滚动速度（1~15）：双指滚动时页面滚动的快慢\n" +
            "· 上屏间隔（100~800 毫秒）：文字上屏间隔，越小上屏越及时\n" +
            "· 主题：老板黑 / 脸色白 / 淤青紫，三种配色风格"
        }
        HelpSection("五、其他") {
            "· 断线后会自动重连上次的电脑；手动点「断开」后不会自动重连\n" +
            "· 电脑端支持托盘常驻与开机自启"
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** 使用说明分组：小节标题 + 内容卡。 */
@Composable
private fun HelpSection(title: String, body: () -> String) {
    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Text(
                body(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(12.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SettingHeader(title: String, onBack: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(56.dp)) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
        } else {
            Spacer(Modifier.width(16.dp))
        }
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SettingItem(
    title: String,
    right: String? = null,
    onClick: () -> Unit,
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 15.dp),
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.weight(1f))
            if (right != null) {
                Text(right, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(4.dp))
            }
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}
