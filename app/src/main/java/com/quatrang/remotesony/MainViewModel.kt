package com.quatrang.remotesony

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.quatrang.remotesony.ir.SonyIr
import com.quatrang.remotesony.ir.TvKey
import com.quatrang.remotesony.tv.AndroidTvRemote
import com.quatrang.remotesony.tv.CertStore
import com.quatrang.remotesony.tv.FoundTv
import com.quatrang.remotesony.tv.Pairing
import com.quatrang.remotesony.tv.TvDiscovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Link { NONE, CONNECTING, CONNECTED, OFFLINE }
enum class PairStep { NONE, STARTING, WAITING_CODE, CHECKING }

data class UiState(
    val tvName: String? = null,
    val host: String? = null,
    val irOnly: Boolean = false,
    val link: Link = Link.NONE,
    val pairStep: PairStep = PairStep.NONE,
    val pairError: String? = null,
    val hasIr: Boolean = false,
    val volume: Int? = null,
    val volumeMax: Int? = null,
    val muted: Boolean = false,
    val currentApp: String? = null,
    val showSetup: Boolean = true,
    val keepAccents: Boolean = false,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("tv", Context.MODE_PRIVATE)
    private val certs = CertStore(app)
    private val ir = SonyIr(app)
    val discovery = TvDiscovery(app)

    private val _state = MutableStateFlow(
        UiState(
            tvName = prefs.getString("name", null),
            host = prefs.getString("host", null),
            irOnly = prefs.getBoolean("irOnly", false),
            hasIr = ir.available,
            showSetup = prefs.getString("host", null) == null && !prefs.getBoolean("irOnly", false),
            keepAccents = prefs.getBoolean("keepAccents", false),
        )
    )
    val state: StateFlow<UiState> = _state

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    /** TV mở ô nhập chữ -> app tự bật bàn phím (kèm nội dung đang có trên TV). */
    private val _keyboardRequests = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val keyboardRequests: SharedFlow<String> = _keyboardRequests

    private var remote: AndroidTvRemote? = null
    private var pairing: Pairing? = null
    private var connectJob: Job? = null
    private var foreground = false
    private var paired: Boolean
        get() = prefs.getBoolean("paired_" + (_state.value.host ?: ""), false)
        set(v) {
            prefs.edit().putBoolean("paired_" + (_state.value.host ?: ""), v).apply()
        }

    private fun toast(msg: String) {
        _messages.tryEmit(msg)
    }

    // ---------- Vòng đời ----------

    fun onForeground() {
        foreground = true
        connect()
    }

    fun onBackground() {
        foreground = false
        connectJob?.cancel()
        remote?.close()
        remote = null
        if (_state.value.link != Link.NONE) _state.update { it.copy(link = Link.OFFLINE) }
    }

    // ---------- Chọn TV / ghép nối ----------

    fun openSetup() {
        _state.update { it.copy(showSetup = true) }
        discovery.start()
    }

    fun closeSetup() {
        discovery.stop()
        if (_state.value.host != null || _state.value.irOnly) _state.update { it.copy(showSetup = false) }
    }

    fun chooseTv(tv: FoundTv) {
        discovery.stop()
        remote?.close(); remote = null
        prefs.edit().putString("name", tv.name).putString("host", tv.host).putBoolean("irOnly", false).apply()
        _state.update {
            it.copy(tvName = tv.name, host = tv.host, irOnly = false, showSetup = false, link = Link.OFFLINE)
        }
        if (paired) connect() else startPairing()
    }

    fun useIrOnly() {
        discovery.stop()
        remote?.close(); remote = null
        prefs.edit().remove("host").putString("name", "TV Sony (hồng ngoại)").putBoolean("irOnly", true).apply()
        _state.update {
            it.copy(tvName = "TV Sony (hồng ngoại)", host = null, irOnly = true, showSetup = false, link = Link.NONE)
        }
    }

    fun startPairing() {
        val host = _state.value.host ?: return
        pairing?.close()
        val p = Pairing(host, certs)
        pairing = p
        _state.update { it.copy(pairStep = PairStep.STARTING, pairError = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { p.start(clientName()) } }
            result.onSuccess {
                _state.update { it.copy(pairStep = PairStep.WAITING_CODE) }
            }.onFailure { e ->
                _state.update { it.copy(pairStep = PairStep.NONE) }
                toast("Không mở được ghép nối: ${e.message ?: "lỗi mạng"}. Kiểm tra TV đã bật và cùng Wi‑Fi.")
            }
        }
    }

    fun submitCode(code: String) {
        val p = pairing ?: return
        _state.update { it.copy(pairStep = PairStep.CHECKING, pairError = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { p.finish(code) } }
            result.onSuccess {
                paired = true
                pairing = null
                _state.update { it.copy(pairStep = PairStep.NONE) }
                toast("Đã ghép nối với TV")
                connect()
            }.onFailure { e ->
                _state.update { it.copy(pairStep = PairStep.WAITING_CODE, pairError = e.message ?: "Lỗi ghép nối") }
            }
        }
    }

    fun cancelPairing() {
        pairing?.close()
        pairing = null
        _state.update { it.copy(pairStep = PairStep.NONE, pairError = null) }
    }

    private fun clientName(): String = "Remote Sony (${Build.MODEL})"

    // ---------- Kết nối điều khiển ----------

    fun connect() {
        val host = _state.value.host ?: return
        if (!foreground || !paired) return
        if (remote?.isReady == true || connectJob?.isActive == true) return
        connectJob = viewModelScope.launch {
            var attempt = 0
            while (foreground) {
                _state.update { it.copy(link = Link.CONNECTING) }
                val r = AndroidTvRemote(host, certs, events)
                val ok = withContext(Dispatchers.IO) { runCatching { r.connect() } }
                if (ok.isSuccess) {
                    remote = r
                    return@launch
                }
                val err = ok.exceptionOrNull()
                if (AndroidTvRemote.isAuthError(err) && attempt >= 1) {
                    // TV không nhận ra điện thoại nữa -> ghép nối lại
                    paired = false
                    _state.update { it.copy(link = Link.OFFLINE) }
                    toast("TV yêu cầu ghép nối lại")
                    startPairing()
                    return@launch
                }
                _state.update { it.copy(link = Link.OFFLINE) }
                attempt++
                delay(if (attempt < 3) 1500L else 5000L)
            }
        }
    }

    fun reconnect() {
        connectJob?.cancel()
        remote?.close(); remote = null
        if (!paired) startPairing() else connect()
    }

    private val events = object : AndroidTvRemote.Events {
        override fun onReady(model: String?) {
            _state.update { it.copy(link = Link.CONNECTED) }
        }

        override fun onClosed(error: Throwable?, wasReady: Boolean) {
            viewModelScope.launch {
                remote = null
                _state.update { it.copy(link = Link.OFFLINE) }
                if (!wasReady && AndroidTvRemote.isAuthError(error)) {
                    paired = false
                    toast("TV yêu cầu ghép nối lại")
                    startPairing()
                } else if (foreground) {
                    delay(1000)
                    connect()
                }
            }
        }

        override fun onKeyboardRequested(currentText: String) {
            _keyboardRequests.tryEmit(currentText)
        }

        override fun onCurrentApp(pkg: String) {
            _state.update { it.copy(currentApp = pkg) }
        }

        override fun onVolume(level: Int, max: Int, muted: Boolean) {
            _state.update { it.copy(volume = level, volumeMax = max, muted = muted) }
        }

        override fun onPowerState(on: Boolean) {}
    }

    // ---------- Gửi lệnh ----------

    private val wifiRemote: AndroidTvRemote? get() = remote?.takeIf { it.isReady }

    fun press(key: TvKey) {
        val r = wifiRemote
        when {
            r != null -> r.sendKey(key.code)
            ir.available && key.irCmd != null -> ir.send(key)
            ir.available -> toast("Nút này cần kết nối Wi‑Fi với TV")
            _state.value.irOnly -> toast("Điện thoại không có mắt hồng ngoại")
            else -> {
                toast("Chưa kết nối được TV qua Wi‑Fi")
                connect()
            }
        }
    }

    private var lastSent = ""

    /** Gọi mỗi khi nội dung ô gõ thay đổi: TV hiện đúng nội dung này. */
    fun typeText(text: String) {
        val r = wifiRemote
        if (r == null) {
            toast("Gõ chữ cần kết nối Wi‑Fi với TV")
            connect()
            return
        }
        // TV CHÈN chữ nhận được vào vị trí con trỏ (không thay cả ô),
        // nên chỉ gửi phần khác nhau: xoá phần cũ bị đổi rồi chèn phần mới.
        // VD bộ gõ Telex đổi "cau" -> "cầu": giữ "c", xoá 2 ký tự, chèn "ầu".
        val target = forTv(text)
        val old = lastSent
        var same = 0
        val max = minOf(old.length, target.length)
        while (same < max && old[same] == target[same]) same++
        if (same > 0 && Character.isHighSurrogate(old[same - 1])) same--
        val deleteCount = old.codePointCount(same, old.length)
        repeat(deleteCount.coerceAtMost(500)) { r.sendKey(TvKey.DEL.code) }
        val insert = target.substring(same)
        if (insert.isNotEmpty()) r.sendText(insert)
        lastSent = target
    }

    /** Ô tìm của nhiều app TV (VD YouTube) không nhận chữ có dấu -> mặc định gửi chữ không dấu. */
    private fun forTv(text: String): String =
        if (_state.value.keepAccents) text else stripAccents(text)

    fun setKeepAccents(keep: Boolean) {
        prefs.edit().putBoolean("keepAccents", keep).apply()
        _state.update { it.copy(keepAccents = keep) }
    }

    fun resetTyped(current: String) {
        lastSent = forTv(current)
    }

    fun launchApp(link: String) {
        val r = wifiRemote
        if (r != null) r.launchApp(link) else toast("Mở ứng dụng cần kết nối Wi‑Fi với TV")
    }

    override fun onCleared() {
        discovery.stop()
        remote?.close()
        pairing?.close()
    }
}

/** "khinh khí cầu" -> "khinh khi cau", "đường" -> "duong". */
fun stripAccents(s: String): String =
    java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace('đ', 'd')
        .replace('Đ', 'D')
