package com.quatrang.remotesony.tv

import android.util.Log
import com.quatrang.remotesony.proto.Framing
import com.quatrang.remotesony.proto.ProtoMsg
import com.quatrang.remotesony.proto.ProtoWriter
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket

/**
 * Kết nối điều khiển (cổng 6466) với Android TV / Google TV.
 * Dùng chung giao thức với app "Google TV" trên điện thoại.
 */
class AndroidTvRemote(
    private val host: String,
    private val certs: CertStore,
    private val events: Events,
) {
    interface Events {
        fun onReady(model: String?)
        fun onClosed(error: Throwable?, wasReady: Boolean)
        fun onKeyboardRequested(currentText: String)
        fun onCurrentApp(pkg: String)
        fun onVolume(level: Int, max: Int, muted: Boolean)
        fun onPowerState(on: Boolean)
    }

    private var socket: SSLSocket? = null
    private var out: OutputStream? = null
    private val writer = Executors.newSingleThreadExecutor()
    @Volatile private var closed = false
    @Volatile private var ready = false
    @Volatile private var imeCounter = 0
    @Volatile private var fieldCounter = 0
    private var features = FEATURES
    private var model: String? = null

    /** Chặn luồng: mở TLS rồi chạy vòng đọc trên luồng riêng. */
    fun connect() {
        val s = Tls.connect(host, 6466, certs, 25000)
        socket = s
        out = BufferedOutputStream(s.outputStream)
        Thread({ readLoop(s) }, "atv-reader").start()
    }

    private fun readLoop(s: SSLSocket) {
        var error: Throwable? = null
        try {
            val input = BufferedInputStream(s.inputStream)
            while (!closed) handle(ProtoMsg.parse(Framing.read(input)))
        } catch (t: Throwable) {
            error = t
        }
        val wasReady = ready
        shutdown()
        if (!closedByUser) events.onClosed(error, wasReady)
    }

    @Volatile private var closedByUser = false

    private fun handle(msg: ProtoMsg) {
        when {
            msg.has(1) -> { // remote_configure
                val cfg = msg.msg(1)
                val info = cfg?.msg(2)
                model = listOfNotNull(info?.string(2), info?.string(1))
                    .filter { it.isNotBlank() }.joinToString(" ").ifBlank { null }
                val tvFeatures = cfg?.int(1) ?: 0
                if (tvFeatures != 0) features = FEATURES and tvFeatures
                send {
                    message(1) {
                        int(1, features)
                        message(2) {
                            int(3, 1)
                            string(4, "1")
                            string(5, "atvremote")
                            string(6, "1.0.0")
                        }
                    }
                }
            }
            msg.has(2) -> send { message(2) { int(1, features) } } // remote_set_active
            msg.has(8) -> { // ping
                val v = msg.msg(8)?.int(1) ?: 0
                send { message(9) { int(1, v) } }
            }
            msg.has(40) -> { // remote_start
                val on = msg.msg(40)?.bool(1) ?: true
                if (!ready) {
                    ready = true
                    events.onReady(model)
                }
                events.onPowerState(on)
            }
            msg.has(20) -> { // remote_ime_key_inject: app hiện tại
                msg.msg(20)?.msg(1)?.string(12)?.let { if (it.isNotBlank()) events.onCurrentApp(it) }
            }
            msg.has(21) -> { // remote_ime_batch_edit: TV báo bộ đếm ô nhập
                msg.msg(21)?.let {
                    imeCounter = it.int(1)
                    fieldCounter = it.int(2)
                }
            }
            msg.has(22) -> { // remote_ime_show_request: TV đang mở bàn phím
                val text = msg.msg(22)?.msg(2)?.string(2) ?: ""
                events.onKeyboardRequested(text)
            }
            msg.has(50) -> msg.msg(50)?.let { // remote_set_volume_level
                events.onVolume(it.int(7), it.int(6), it.bool(8))
            }
            msg.has(3) -> Log.w(TAG, "TV báo lỗi: $msg")
        }
    }

    private fun send(block: ProtoWriter.() -> Unit) {
        val data = ProtoWriter().apply(block).toByteArray()
        if (closed) return
        runCatching { // executor có thể vừa shutdown (RejectedExecutionException)
            writer.execute {
                try {
                    val o = out ?: return@execute
                    Framing.write(o, data)
                } catch (t: Throwable) {
                    Log.w(TAG, "Gửi lỗi", t)
                    runCatching { socket?.close() }
                }
            }
        }
    }

    fun sendKey(keyCode: Int) = send {
        message(10) { int(1, keyCode); int(2, DIRECTION_SHORT) }
    }

    /** Ghi toàn bộ nội dung [text] vào ô nhập đang mở trên TV. */
    fun sendText(text: String) {
        if (text.isEmpty()) return
        val pos = text.length - 1
        send {
            message(21) {
                int(1, imeCounter)
                int(2, fieldCounter)
                message(3) {
                    int(1, 1)
                    message(2) { int(1, pos); int(2, pos); string(3, text) }
                }
            }
        }
    }

    fun launchApp(link: String) = send { message(90) { string(1, link) } }

    val isReady get() = ready && !closed

    fun close() {
        closedByUser = true
        shutdown()
    }

    private fun shutdown() {
        if (closed) return
        closed = true
        ready = false
        // Đóng TLS có thể ghi close_notify -> không làm trên luồng chính
        val s = socket
        if (s != null) Thread { runCatching { s.close() } }.start()
        writer.shutdown()
    }

    companion object {
        private const val TAG = "AtvRemote"
        private const val DIRECTION_SHORT = 3
        // PING | KEY | IME | POWER | VOLUME | APP_LINK
        const val FEATURES = 1 or 2 or 4 or 32 or 64 or 512

        /** Lỗi chứng chỉ khi bắt tay TLS = TV chưa (hoặc không còn) nhận ra điện thoại này. */
        fun isAuthError(t: Throwable?): Boolean {
            var e = t
            while (e != null) {
                if (e is SSLException) return true
                e = e.cause
            }
            return false
        }
    }
}
