package com.fingertip.app.net

import android.util.Log
import kotlinx.coroutines.channels.Channel
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 指间 WebSocket 客户端。
 *
 * 职责：
 * - 连接电脑端服务（局域网）
 * - 发送 JSON 指令（鼠标移动/点击/键盘/文字）
 * - 上报连接状态变化
 *
 * 协议见《开发指南.md》4.4 节。
 */
class WsClient(
    private val onStatusChanged: (ConnectionState, String) -> Unit,
) {
    sealed interface ConnectionState {
        data object Connected : ConnectionState
        data object Connecting : ConnectionState
        data class Disconnected(val reason: String) : ConnectionState
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null
    private var deviceName: String = ""

    val deviceId: String = UUID.randomUUID().toString()

    @Volatile
    var isConnected: Boolean = false
        private set

    /** 建立连接（带配对码，首次连接使用）。 */
    fun connect(host: String, port: Int, pairCode: String?) {
        disconnect()
        onStatusChanged(ConnectionState.Connecting, "")
        val url = "ws://$host:$port/"
        val request = Request.Builder().url(url).build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket 已打开")
                // 首帧发送配对信息；已配对设备由服务端记录 deviceId 直接放行
                val payload = JSONObject()
                payload.put("type", "auth")
                payload.put("code", pairCode ?: "")
                payload.put("deviceId", deviceId)
                webSocket.send(payload.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleServerMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleServerMessage(bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isConnected = false
                onStatusChanged(ConnectionState.Disconnected("closed"), deviceName)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "连接失败: ${t.message}")
                isConnected = false
                onStatusChanged(ConnectionState.Disconnected(t.message ?: "无法连接"), deviceName)
            }
        }
        ws = client.newWebSocket(request, listener)
    }

    private fun handleServerMessage(text: String) {
        try {
            val msg = JSONObject(text)
            when (msg.optString("type")) {
                "connected" -> {
                    isConnected = true
                    deviceName = msg.optString("deviceName")
                    onStatusChanged(ConnectionState.Connected, deviceName)
                }
                "auth_failed" -> {
                    isConnected = false
                    onStatusChanged(ConnectionState.Disconnected("配对码错误"), deviceName)
                }
                "disconnected" -> {
                    isConnected = false
                    onStatusChanged(ConnectionState.Disconnected(msg.optString("reason", "断开")), deviceName)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "消息解析失败: $text", e)
        }
    }

    /** 发送一条 JSON 指令。 */
    fun send(type: String, payload: Map<String, Any?> = emptyMap()) {
        val ws = this.ws ?: return
        if (!isConnected && type != "auth") return
        val json = JSONObject().apply {
            put("type", type)
            payload.forEach { (k, v) -> put(k, v) }
        }
        ws.send(json.toString())
    }

    fun disconnect() {
        ws?.close(1000, "bye")
        ws = null
        isConnected = false
    }

    companion object {
        private const val TAG = "Fingertip.Ws"
    }
}
