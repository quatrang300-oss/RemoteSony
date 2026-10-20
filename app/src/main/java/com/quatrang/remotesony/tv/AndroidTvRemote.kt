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
            msg.has(30) -> { // remote_voice_begin: TV sẵn sàng nghe
                val id = msg.msg(30)?.int(1) ?: 0
                voiceWaiter?.let { if (!it.isDone) it.complete(id) }
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

    /** Bấm một phím ký tự; [shift] = giữ Shift trong lúc bấm (chữ hoa, ! ? : …). Gửi theo đúng thứ tự. */
    fun sendCharKey(keyCode: Int, shift: Boolean) {
        if (!shift) { sendKey(keyCode); return }
        send { message(10) { int(1, TvChars.SHIFT_LEFT); int(2, DIRECTION_START_LONG) } }
        sendKey(keyCode)
        send { message(10) { int(1, TvChars.SHIFT_LEFT); int(2, DIRECTION_END_LONG) } }
    }

    /** Ghi toàn bộ nội dung [text] vào ô nhập đang mở trên TV. */
    fun sendText(text: String) {
        if (text.isEmpty()) return
        send { textMsg(text) }
    }

    private fun ProtoWriter.textMsg(text: String) {
        val pos = text.length - 1
        message(21) {
            int(1, imeCounter)
            int(2, fieldCounter)
            message(3) {
                int(1, 1)
                message(2) { int(1, pos); int(2, pos); string(3, text) }
            }
        }
    }

    /**
     * Xoá [deleteCount] ký tự trước con trỏ rồi chèn [insert], ĐÚNG THỨ TỰ.
     * Phím xoá đi đường "phím bấm" còn chữ đi đường "bộ gõ" của TV: hai đường này TV xử lý
     * không theo thứ tự, nên nếu gửi liền nhau chữ mới có thể được chèn trước rồi bị chính
     * các phím xoá xoá mất (VD "cau" -> "cầu" thành "cau"). Vì vậy chờ TV xoá xong rồi mới chèn.
     * Trả về Future hoàn tất khi đã gửi xong (để bộ đồng bộ gộp các thay đổi gõ nhanh).
     */
    fun edit(deleteCount: Int, insert: String): java.util.concurrent.Future<*>? {
        if (closed) return null
        val del = ProtoWriter().apply { message(10) { int(1, KEY_DEL); int(2, DIRECTION_SHORT) } }.toByteArray()
        return runCatching {
            writer.submit(Runnable {
                try {
                    val o = out ?: return@Runnable
                    repeat(deleteCount) { i ->
                        Framing.write(o, del)
                        if (i % 8 == 7) Thread.sleep(15)
                    }
                    if (insert.isNotEmpty()) {
                        if (deleteCount > 0) Thread.sleep((90L + 20L * deleteCount).coerceAtMost(1500L))
                        // dựng gói chữ SAU khi chờ: bộ đếm ô nhập TV gửi về có thể đã đổi
                        Framing.write(o, ProtoWriter().apply { textMsg(insert) }.toByteArray())
                    }
                } catch (t: InterruptedException) {
                } catch (t: Throwable) {
                    Log.w(TAG, "Gửi lỗi", t)
                    runCatching { socket?.close() }
                }
            })
        }.getOrNull()
    }

    // ---------------- Giọng nói ----------------
    @Volatile private var voiceWaiter: java.util.concurrent.CompletableFuture<Int>? = null
    @Volatile private var voiceSession = 0
    private val voiceBuf = java.io.ByteArrayOutputStream()

    /** TV có nhận micro từ remote không (bit VOICE trong bảng tính năng đã thoả thuận). */
    val voiceSupported get() = features and FEATURE_VOICE != 0

    /**
     * Bắt đầu phiên giọng nói: bấm phím SEARCH, chờ TV trả remote_voice_begin rồi xác nhận lại.
     * Chặn luồng (tối đa [timeoutMs]); trả true nếu TV đã sẵn sàng nhận âm thanh.
     */
    fun startVoice(timeoutMs: Long = 2500): Boolean {
        if (closed || !voiceSupported) return false
        val w = java.util.concurrent.CompletableFuture<Int>()
        voiceWaiter = w
        synchronized(voiceBuf) { voiceBuf.reset() }
        sendKey(KEY_SEARCH)
        val id = runCatching { w.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) }.getOrNull()
        voiceWaiter = null
        if (id == null || closed) return false
        voiceSession = id
        send { message(30) { int(1, id) } }
        return true
    }

    /** Âm thanh PCM 16‑bit, 8 kHz, mono. Gom thành gói 8–20 KB như app Google TV. */
    fun sendVoice(pcm: ByteArray, len: Int = pcm.size) {
        if (closed) return
        synchronized(voiceBuf) {
            voiceBuf.write(pcm, 0, len)
            if (voiceBuf.size() >= VOICE_MIN_CHUNK) flushVoice(false)
        }
    }

    private fun flushVoice(pad: Boolean) {
        val all = voiceBuf.toByteArray()
        voiceBuf.reset()
        var i = 0
        while (i < all.size) {
            val n = minOf(VOICE_MAX_CHUNK, all.size - i)
            var chunk = all.copyOfRange(i, i + n)
            if (pad && chunk.size < VOICE_MIN_CHUNK) chunk = chunk.copyOf(VOICE_MIN_CHUNK) // đệm im lặng
            val id = voiceSession
            send { message(31) { int(1, id); bytes(2, chunk) } }
            i += n
        }
    }

    /** Kết thúc phiên: gửi nốt phần âm thanh còn lại rồi báo TV xử lý. */
    fun endVoice() {
        if (closed) return
        synchronized(voiceBuf) { if (voiceBuf.size() > 0) flushVoice(true) }
        val id = voiceSession
        send { message(32) { int(1, id) } }
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
        private const val DIRECTION_START_LONG = 1
        private const val DIRECTION_END_LONG = 2
        private const val KEY_DEL = 67
        private const val KEY_SEARCH = 84
        private const val FEATURE_VOICE = 8
        private const val VOICE_MIN_CHUNK = 8 * 1024
        private const val VOICE_MAX_CHUNK = 20 * 1024
        // PING | KEY | IME | VOICE | POWER | VOLUME | APP_LINK
        const val FEATURES = 1 or 2 or 4 or 8 or 32 or 64 or 512

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
