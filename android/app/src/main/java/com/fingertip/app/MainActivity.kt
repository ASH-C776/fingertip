package com.fingertip.app

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.AbsoluteSizeSpan
import android.util.Log
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.fingertip.app.net.WsClient
import com.fingertip.app.ui.DiscoveredDevice
import com.fingertip.app.ui.MainScreen
import com.fingertip.app.ui.SettingPage
import com.fingertip.app.ui.SettingsScreen
import com.fingertip.app.ui.ThemeMode
import com.fingertip.app.ui.theme.FingertipTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * 指间 Android 端主 Activity。
 * 管理：连接状态、页面导航（主界面 / 设置）、键盘上屏。
 */
class MainActivity : ComponentActivity() {

    private lateinit var ws: WsClient
    private lateinit var prefs: SharedPreferences
    private val discoverScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main
    )

    override fun onDestroy() {
        super.onDestroy()
        discoverScope.cancel()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 阶段 9：沉浸式 —— 状态栏/导航栏透明，主题色铺满整屏
        enableEdgeToEdge()
        prefs = getSharedPreferences("fingertip_prefs", MODE_PRIVATE)

        var status by mutableStateOf<WsClient.ConnectionState>(WsClient.ConnectionState.Disconnected("未连接"))
        var deviceName by mutableStateOf(prefs.getString("device_name", "") ?: "")
        var page by mutableStateOf<SettingPage?>(null)
        var keyboardOpen by mutableStateOf(false)
        var host by mutableStateOf(prefs.getString("host", "192.168.31.26") ?: "192.168.31.26")
        var port by mutableStateOf(prefs.getString("port", "8765") ?: "8765")

        // 阶段 7：设置项（SharedPreferences 持久化，实时生效）
        var themeMode by mutableStateOf(
            runCatching { ThemeMode.valueOf(prefs.getString("theme", "FACE_WHITE") ?: "FACE_WHITE") }
                .getOrDefault(ThemeMode.FACE_WHITE)
        )
        var slideRatio by mutableStateOf(prefs.getFloat("slide_ratio", 2.5f))
        var sensitivity by mutableStateOf(prefs.getFloat("sensitivity", 1.0f))
        var scrollSpeed by mutableStateOf(prefs.getFloat("scroll_speed", 5f))
        var syncIntervalMs by mutableStateOf(prefs.getInt("sync_interval_ms", 200))

        fun saveSettings() {
            prefs.edit()
                .putString("theme", themeMode.name)
                .putFloat("slide_ratio", slideRatio)
                .putFloat("sensitivity", sensitivity)
                .putFloat("scroll_speed", scrollSpeed)
                .putInt("sync_interval_ms", syncIntervalMs)
                .apply()
        }

        // 阶段 7：断线自动重连（非主动断开时，3 秒后重试上次设备）
        var reconnectJob: Job? = null
        var userDisconnected by mutableStateOf(false)
        var lastConnHost by mutableStateOf(prefs.getString("host", "") ?: "")

        // 阶段 6：设备发现
        var discoverOpen by mutableStateOf(false)
        var searching by mutableStateOf(false)
        var devices by mutableStateOf(listOf<com.fingertip.app.ui.DiscoveredDevice>())
        var discoverJob: Job? = null

        ws = WsClient { conn, name ->
            status = conn
            when (conn) {
                is WsClient.ConnectionState.Connected -> {
                    deviceName = name
                    userDisconnected = false
                    lastConnHost = host
                    prefs.edit().putString("device_name", name).apply()
                }
                is WsClient.ConnectionState.Disconnected -> {
                    // 非主动断开且曾连过：3 秒后自动重连（仅重试已记住设备）
                    if (!userDisconnected && lastConnHost.isNotBlank()) {
                        reconnectJob?.cancel()
                        reconnectJob = discoverScope.launch {
                            delay(3000)
                            val p = prefs.getString("port", "8765") ?: "8765"
                            ws.connect(lastConnHost, p.trim().toIntOrNull() ?: 8765, null)
                        }
                    }
                }
                is WsClient.ConnectionState.Connecting -> {}
            }
        }

        // 启动自动重连：读取上次连接的设备，仅尝试一次（阶段 7 完整实现）
        val savedHost = prefs.getString("host", "") ?: ""
        val savedPort = prefs.getString("port", "8765") ?: "8765"
        if (savedHost.isNotBlank()) {
            ws.connect(savedHost.trim(), savedPort.trim().toIntOrNull() ?: 8765, null)
        }

        fun goBack() {
            when {
                discoverOpen -> discoverOpen = false
                page != null -> page = if (page == SettingPage.MAIN) null else SettingPage.MAIN
                keyboardOpen -> keyboardOpen = false
                // 主界面：默认返回键行为（退出 App）
            }
        }

        fun startDiscovery() {
            discoverJob?.cancel()
            searching = true
            devices = emptyList()
            discoverOpen = true
            val lock = acquireMulticastLock()
            discoverJob = discoverScope.launch(Dispatchers.IO) {
                try {
                    devices = searchUdpDevices(6000L)
                } finally {
                    lock?.release()
                    searching = false
                }
            }
        }

        fun connectDevice(d: com.fingertip.app.ui.DiscoveredDevice) {
            host = d.ip
            port = d.port.toString()
            ws.connect(d.ip, d.port, null)
            prefs.edit()
                .putString("host", d.ip)
                .putString("port", d.port.toString())
                .apply()
            discoverOpen = false
        }

        setContent {
            // 阶段 7：主题（老板黑 / 脸色白 / 淤青紫）
            FingertipTheme(themeMode = themeMode) {
                // 阶段 9：系统栏配色跟随主题。
                // 状态栏/导航栏用主题背景色（不透明）而非透明：透明导航栏 + 浅色图标请求时，
                // HyperOS 等 ROM 会自行叠加白色 scrim 保手势条可见，与 App 背景（如脸色白
                // #F2F2F7）明显割裂（用户反馈「底下小黄条不协调」）。不透明+同色即无缝。
                // 图标明暗同样跟随主题（深色主题=浅色图标）。
                val view = LocalView.current
                // 主题背景色在组合作用域读取（SideEffect lambda 非 Composable，不能读 MaterialTheme）
                val barColor = MaterialTheme.colorScheme.background.toArgb()
                if (!view.isInEditMode) {
                    SideEffect {
                        val window = (view.context as Activity).window
                        val controller = WindowCompat.getInsetsController(window, view)
                        val isDark = themeMode != ThemeMode.FACE_WHITE
                        controller.isAppearanceLightStatusBars = !isDark
                        controller.isAppearanceLightNavigationBars = !isDark
                        window.statusBarColor = barColor
                        window.navigationBarColor = barColor
                        // 去掉导航栏上方系统分隔细线，避免与背景色形成接缝（API 29+）
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            window.navigationBarDividerColor = android.graphics.Color.TRANSPARENT
                        }
                    }
                }
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    // 页面切换：淡入淡出，避免硬切
                    val rootRoute = when {
                        page != null -> "settings"
                        keyboardOpen -> "keyboard"
                        else -> "main"
                    }
                    Crossfade(targetState = rootRoute, label = "rootRoute", animationSpec = tween(200)) {
                    when {
                        page != null -> {
                            // 设置页：返回键先回设置一级，再回主界面
                            BackHandler { goBack() }
                            SettingsScreen(
                                page = page!!,
                                connection = status,
                                deviceName = deviceName,
                                ws = ws,
                                host = host,
                                port = port,
                                themeMode = themeMode,
                                slideRatio = slideRatio,
                                sensitivity = sensitivity,
                                scrollSpeed = scrollSpeed,
                                syncIntervalMs = syncIntervalMs,
                                onHostChange = { host = it },
                                onPortChange = { port = it },
                                onConnect = {
                                    userDisconnected = false
                                    ws.connect(host.trim(), port.trim().toIntOrNull() ?: 8765, null)
                                    prefs.edit()
                                        .putString("host", host.trim())
                                        .putString("port", port.trim())
                                        .apply()
                                },
                                onDisconnect = {
                                    userDisconnected = true
                                    ws.disconnect()
                                },
                                onThemeChange = {
                                    themeMode = it
                                    saveSettings()
                                },
                                onSlideRatioChange = {
                                    slideRatio = it
                                    saveSettings()
                                },
                                onSensitivityChange = {
                                    sensitivity = it
                                    saveSettings()
                                },
                                onScrollSpeedChange = {
                                    scrollSpeed = it
                                    saveSettings()
                                },
                                onSyncIntervalChange = {
                                    syncIntervalMs = it
                                    saveSettings()
                                },
                                onNavigate = { page = it },
                                onBack = { goBack() },
                                onDiscover = { startDiscovery() },
                            )
                        }
                        keyboardOpen -> {
                            // 键盘面板：返回键收起键盘回主界面
                            BackHandler { keyboardOpen = false }
                            KeyboardPanel(
                                ws = ws,
                                syncIntervalMs = syncIntervalMs,
                                onClose = { keyboardOpen = false },
                            )
                        }
                        else -> MainScreen(
                            connection = status,
                            deviceName = deviceName,
                            ws = ws,
                            slideRatio = slideRatio * sensitivity,
                            scrollSpeed = scrollSpeed.toInt(),
                            onOpenSettings = { page = SettingPage.MAIN },
                            onOpenKeyboard = { keyboardOpen = true },
                            onDiscover = { startDiscovery() },
                        )
                    }
                    }

                    // 设备发现弹窗（未连接时从状态栏「发现设备」打开）
                    if (discoverOpen) {
                        com.fingertip.app.ui.DiscoverDialog(
                            searching = searching,
                            devices = devices,
                            onSelect = { connectDevice(it) },
                            onRescan = { startDiscovery() },
                            onDismiss = { discoverOpen = false },
                        )
                    }
                }
            }
        }
    }

    /** WiFi 多播锁：部分 ROM 需要持锁才能收发 UDP 广播/组播。 */
    private fun acquireMulticastLock(): android.net.wifi.WifiManager.MulticastLock? {
        return try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            wifi.createMulticastLock("fingertip_discovery").apply { acquire() }
        } catch (e: Exception) {
            Log.w(TAG, "多播锁获取失败", e)
            null
        }
    }

    /** UDP 设备搜索：先发探测包（电脑单播回复），再监听广播+单播，返回去重设备。 */
    private fun searchUdpDevices(durationMs: Long): List<DiscoveredDevice> {
        val found = LinkedHashMap<String, DiscoveredDevice>()

        // 1) 发探测包：让电脑端单播回复（路由器隔离广播时仍能发现）
        try {
            val probeSocket = DatagramSocket()
            probeSocket.broadcast = true
            val probe = "{\"type\":\"probe\"}".toByteArray(Charsets.UTF_8)
            probeSocket.send(DatagramPacket(probe, probe.size, InetAddress.getByName("255.255.255.255"), 8765))
            probeSocket.close()
            Log.i(TAG, "探测包已发送")
        } catch (e: Exception) {
            Log.w(TAG, "探测包发送失败", e)
        }

        // 2) 监听 8765：接收电脑广播 + 单播回复
        val socket = DatagramSocket(null)
        try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(8765))
            socket.soTimeout = 200
            val deadline = System.currentTimeMillis() + durationMs
            while (System.currentTimeMillis() < deadline) {
                val buf = ByteArray(1024)
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    continue
                } catch (e: Exception) {
                    break
                }
                val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                try {
                    val json = JSONObject(text)
                    if (json.optString("type") == "fingertip") {
                        val ip = json.optString("ip").trim()
                        val port = json.optInt("port", 8765)
                        val name = json.optString("name").ifBlank { ip }
                        if (ip.isNotBlank()) {
                            found["$ip:$port"] = DiscoveredDevice(name, ip, port)
                            Log.i(TAG, "发现设备: $name $ip:$port")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "发现包解析失败: $text")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "UDP 搜索异常", e)
        } finally {
            socket.close()
        }
        return found.values.toList()
    }

    companion object {
        private const val TAG = "Fingertip.Main"
    }
}

/**
 * 阶段 5：键盘与文字上屏。
 *
 * 输入框用 Android 原生 EditText（AndroidView 嵌入），走系统标准输入链路。
 * 轮询上屏：内容变化后节流 400ms，把「增量」同步到电脑（打字自动上屏）；
 * 输入法发送键/回车 = 发 enter 到电脑并清空。
 */
@Composable
private fun KeyboardPanel(ws: WsClient, syncIntervalMs: Int = 200, onClose: () -> Unit) {
    var text by mutableStateOf("")
    val context = LocalContext.current
    val editTextRef = remember { mutableStateOf<EditText?>(null) }
    val scope = rememberCoroutineScope()
    var pendingJob by remember { mutableStateOf<Job?>(null) }
    var lastSentText by remember { mutableStateOf("") }

    fun syncToPc(oldText: String, newText: String) {
        when {
            newText.isEmpty() && oldText.isNotEmpty() ->
                ws.send("key", mapOf("key" to "backspace", "times" to oldText.length))

            newText.startsWith(oldText) -> {
                val add = newText.removePrefix(oldText)
                if (add.isNotEmpty()) {
                    ws.send("text", mapOf("content" to add))
                }
            }

            oldText.startsWith(newText) -> {
                val del = oldText.length - newText.length
                if (del > 0) {
                    ws.send("key", mapOf("key" to "backspace", "times" to del))
                }
            }

            else -> {
                // 中间插入/复杂修改：全选后替换
                ws.send("key", mapOf("key" to "select_all"))
                ws.send("text", mapOf("content" to newText))
            }
        }
    }

    fun scheduleSync(newText: String) {
        pendingJob?.cancel()
        pendingJob = scope.launch {
            delay(syncIntervalMs.coerceIn(80, 2000).toLong()) // 上屏间隔（设置页可调）
            if (newText != lastSentText) {
                syncToPc(lastSentText, newText)
                lastSentText = newText
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "收起键盘")
            }
            Text("键盘上屏", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(12.dp))

        // 输入框占满剩余空间（提示小字嵌在输入框内，跟随主题）
        val colors = MaterialTheme.colorScheme
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            factory = { ctx ->
                EditText(ctx).apply {
                    val d = resources.displayMetrics.density
                    // 两行 hint：第一行主提示，第二行小字说明上屏间隔
                    fun buildHint(): CharSequence {
                        val hs = SpannableString("输入文字或语音输入\n打字自动上屏，间隔 $syncIntervalMs 毫秒")
                        hs.setSpan(AbsoluteSizeSpan(16, true), 0, 9, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        hs.setSpan(AbsoluteSizeSpan(12, true), 10, hs.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        return hs
                    }
                    hint = buildHint()
                    textSize = 16f
                    // 与 Compose 端排版一致：去掉系统为拉丁字体预留的上下 padding（中文更居中）
                    setIncludeFontPadding(false)
                    gravity = Gravity.TOP or Gravity.START
                    background = GradientDrawable().apply {
                        cornerRadius = 12 * d
                        setColor(colors.surfaceVariant.toArgb())
                        setStroke((1 * d).toInt(), colors.outlineVariant.toArgb())
                    }
                    setTextColor(colors.onSurface.toArgb())
                    setHintTextColor(colors.onSurfaceVariant.toArgb())
                    setPadding((16 * d).toInt(), (12 * d).toInt(), (16 * d).toInt(), (12 * d).toInt())
                    minLines = 3
                    maxLines = 10
                    isVerticalScrollBarEnabled = false
                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                        override fun afterTextChanged(editable: Editable?) {
                            val s = editable?.toString() ?: ""
                            text = s
                            scheduleSync(s)
                        }
                    })
                    editTextRef.value = this
                }
            },
            update = { et ->
                // 主题/上屏间隔变化时更新颜色与提示
                val d = et.resources.displayMetrics.density
                et.background = GradientDrawable().apply {
                    cornerRadius = 12 * d
                    setColor(colors.surfaceVariant.toArgb())
                    setStroke((1 * d).toInt(), colors.outlineVariant.toArgb())
                }
                et.setTextColor(colors.onSurface.toArgb())
                et.setHintTextColor(colors.onSurfaceVariant.toArgb())
                val hs = SpannableString("输入文字或语音输入\n打字自动上屏，间隔 $syncIntervalMs 毫秒")
                hs.setSpan(AbsoluteSizeSpan(16, true), 0, 9, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                hs.setSpan(AbsoluteSizeSpan(12, true), 10, hs.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                et.hint = hs
                val s = et.text?.toString() ?: ""
                if (s != text) {
                    et.setText(text)
                }
            },
        )

        Spacer(Modifier.height(10.dp))
    }

    // 面板挂载到窗口后，聚焦原生 EditText 并弹出输入法（系统标准通道，对第三方 IME 可靠）
    LaunchedEffect(Unit) {
        delay(300)
        editTextRef.value?.let { et ->
            et.post {
                et.requestFocus()
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }
}
