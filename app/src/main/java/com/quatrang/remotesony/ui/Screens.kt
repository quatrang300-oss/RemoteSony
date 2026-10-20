@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.quatrang.remotesony.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.Input
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.quatrang.remotesony.Link
import com.quatrang.remotesony.MainViewModel
import com.quatrang.remotesony.PairStep
import com.quatrang.remotesony.UiState
import com.quatrang.remotesony.ir.TvKey
import com.quatrang.remotesony.tv.FoundTv
import kotlinx.coroutines.delay

private const val YOUTUBE = "https://www.youtube.com"
private const val NETFLIX = "https://www.netflix.com/title"

@Composable
fun App(vm: MainViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    var keyboardOpen by rememberSaveable { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) {
        vm.keyboardRequests.collect { current ->
            text = current
            vm.resetTyped(current)
            keyboardOpen = true
        }
    }

    BackHandler(enabled = keyboardOpen && !s.showSetup) { keyboardOpen = false }
    BackHandler(enabled = s.showSetup && (s.host != null || s.irOnly)) { vm.closeSetup() }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            when {
                s.showSetup -> SetupScreen(vm, s)
                keyboardOpen -> KeyboardPanel(
                    vm = vm,
                    s = s,
                    text = text,
                    onText = {
                        text = it
                        vm.typeText(it)
                    },
                    onClose = { keyboardOpen = false },
                )
                else -> RemoteScreen(vm, s, onKeyboard = {
                    text = ""
                    vm.resetTyped("")
                    keyboardOpen = true
                })
            }
        }
    }

    if (s.pairStep != PairStep.NONE) PairDialog(vm, s)
}

// ======================= Màn hình remote =======================

@Composable
private fun Header(vm: MainViewModel, s: UiState) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(s.tvName ?: "TV Sony", fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(
                s.host ?: "Hồng ngoại",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LinkPill(vm, s)
        if (s.hasIr) {
            Spacer(Modifier.width(6.dp))
            StatusPill(
                "IR",
                MaterialTheme.colorScheme.primaryContainer,
                MaterialTheme.colorScheme.onPrimaryContainer,
                Icons.Filled.SettingsRemote,
            )
        }
        IconButton(onClick = { vm.openSetup() }) {
            Icon(Icons.Filled.Settings, contentDescription = "Chọn TV")
        }
    }
}

@Composable
private fun LinkPill(vm: MainViewModel, s: UiState) {
    if (s.irOnly || s.host == null) return
    val ok = Color(0xFF2E9E5B)
    when (s.link) {
        Link.CONNECTED -> StatusPill("Wi‑Fi", ok.copy(alpha = 0.18f), ok, Icons.Filled.Wifi)
        Link.CONNECTING -> StatusPill(
            "Đang nối…",
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            Icons.Filled.Wifi,
        )
        else -> StatusPill(
            "Mất Wi‑Fi",
            MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
            MaterialTheme.colorScheme.error,
            Icons.Filled.WifiOff,
            onClick = { vm.reconnect() },
        )
    }
}

@Composable
private fun RemoteScreen(vm: MainViewModel, s: UiState, onKeyboard: () -> Unit) {
    var showNumbers by rememberSaveable { mutableStateOf(false) }
    val gap = 10.dp
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Header(vm, s)
        Spacer(Modifier.height(14.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            KeyButton(
                Modifier.weight(1f).height(56.dp),
                contentColor = MaterialTheme.colorScheme.error,
                onPress = { vm.press(TvKey.POWER) },
            ) { KeyIcon(Icons.Filled.PowerSettingsNew, "Nguồn", 28) }
            KeyButton(Modifier.weight(1f).height(56.dp), onPress = { vm.press(TvKey.INPUT) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    KeyIcon(Icons.AutoMirrored.Filled.Input, "Nguồn vào", 20)
                    Spacer(Modifier.width(6.dp)); KeyLabel("Input")
                }
            }
            KeyButton(
                Modifier.weight(1f).height(56.dp),
                container = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                border = false,
                onPress = onKeyboard,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    KeyIcon(Icons.Filled.Keyboard, "Bàn phím", 20)
                    Spacer(Modifier.width(6.dp)); KeyLabel("Gõ chữ")
                }
            }
        }

        Spacer(Modifier.height(22.dp))
        DPad(vm)
        Spacer(Modifier.height(22.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            KeyButton(Modifier.weight(1f).height(52.dp), onPress = { vm.press(TvKey.BACK) }) {
                KeyIcon(Icons.AutoMirrored.Filled.ArrowBack, "Quay lại")
            }
            KeyButton(Modifier.weight(1f).height(52.dp), onPress = { vm.press(TvKey.HOME) }) {
                KeyIcon(Icons.Filled.Home, "Màn hình chính")
            }
            KeyButton(Modifier.weight(1f).height(52.dp), onPress = { vm.press(TvKey.MENU) }) {
                KeyIcon(Icons.Filled.Menu, "Tuỳ chọn")
            }
        }

        Spacer(Modifier.height(gap))
        Row(Modifier.fillMaxWidth().height(132.dp), horizontalArrangement = Arrangement.spacedBy(gap)) {
            RockerKey(Modifier.weight(1f), "VOL", { vm.press(TvKey.VOL_UP) }, { vm.press(TvKey.VOL_DOWN) })
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(gap)) {
                KeyButton(Modifier.fillMaxWidth().weight(1f), onPress = { vm.press(TvKey.MUTE) }) {
                    KeyIcon(Icons.AutoMirrored.Filled.VolumeOff, "Tắt tiếng")
                }
                KeyButton(
                    Modifier.fillMaxWidth().weight(1f),
                    container = if (showNumbers) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                    onPress = { showNumbers = !showNumbers },
                ) { KeyLabel("123") }
            }
            RockerKey(Modifier.weight(1f), "CH", { vm.press(TvKey.CH_UP) }, { vm.press(TvKey.CH_DOWN) })
        }

        if (s.volume != null && s.volumeMax != null && s.volumeMax > 0) {
            Spacer(Modifier.height(6.dp))
            Text(
                if (s.muted) "Âm lượng: tắt tiếng" else "Âm lượng: ${s.volume}/${s.volumeMax}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (showNumbers) {
            Spacer(Modifier.height(gap))
            NumberPad(vm)
        }

        Spacer(Modifier.height(18.dp))
        Text(
            "Ứng dụng",
            modifier = Modifier.fillMaxWidth(),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            KeyButton(Modifier.weight(1f).height(48.dp), onPress = { vm.launchApp(YOUTUBE) }) {
                KeyLabel("YouTube")
            }
            KeyButton(Modifier.weight(1f).height(48.dp), onPress = { vm.launchApp(NETFLIX) }) {
                KeyLabel("Netflix")
            }
            KeyButton(Modifier.weight(1f).height(48.dp), onPress = { vm.press(TvKey.PLAY_PAUSE) }) {
                KeyIcon(Icons.Filled.PlayArrow, "Phát / tạm dừng")
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun DPad(vm: MainViewModel) {
    val ring = MaterialTheme.colorScheme.surfaceContainerHigh
    Box(
        Modifier.size(236.dp).background(ring, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        val arrow = Modifier.size(76.dp)
        val clear = Color.Transparent
        KeyButton(arrow.align(Alignment.TopCenter), repeat = true, container = clear, shape = CircleShape,
            border = false, onPress = { vm.press(TvKey.UP) }) {
            KeyIcon(Icons.Filled.KeyboardArrowUp, "Lên", 34)
        }
        KeyButton(arrow.align(Alignment.BottomCenter), repeat = true, container = clear, shape = CircleShape,
            border = false, onPress = { vm.press(TvKey.DOWN) }) {
            KeyIcon(Icons.Filled.KeyboardArrowDown, "Xuống", 34)
        }
        KeyButton(arrow.align(Alignment.CenterStart), repeat = true, container = clear, shape = CircleShape,
            border = false, onPress = { vm.press(TvKey.LEFT) }) {
            KeyIcon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Trái", 34)
        }
        KeyButton(arrow.align(Alignment.CenterEnd), repeat = true, container = clear, shape = CircleShape,
            border = false, onPress = { vm.press(TvKey.RIGHT) }) {
            KeyIcon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Phải", 34)
        }
        KeyButton(
            Modifier.size(96.dp),
            container = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = CircleShape,
            onPress = { vm.press(TvKey.OK) },
        ) { KeyLabel("OK", 18) }
    }
}

@Composable
private fun RockerKey(modifier: Modifier, label: String, onUp: () -> Unit, onDown: () -> Unit) {
    Column(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(18.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        KeyButton(Modifier.fillMaxWidth().weight(1f), repeat = true, container = Color.Transparent,
            border = false, shape = RoundedCornerShape(18.dp), onPress = onUp) { KeyLabel("+", 22) }
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        KeyButton(Modifier.fillMaxWidth().weight(1f), repeat = true, container = Color.Transparent,
            border = false, shape = RoundedCornerShape(18.dp), onPress = onDown) { KeyLabel("−", 22) }
    }
}

@Composable
private fun NumberPad(vm: MainViewModel) {
    val rows = TvKey.digits.chunked(3)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { key ->
                    KeyButton(Modifier.weight(1f).height(46.dp), onPress = { vm.press(key) }) {
                        KeyLabel(key.name.removePrefix("D"), 18)
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

// ======================= Bàn phím =======================

@Composable
private fun KeyboardPanel(
    vm: MainViewModel,
    s: UiState,
    text: String,
    onText: (String) -> Unit,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current

    LaunchedEffect(Unit) {
        delay(120)
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }

    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Về remote") }
            Text("Bàn phím TV", fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            LinkPill(vm, s)
        }
        Text(
            "Chữ bạn gõ hiện ngay trên ô tìm kiếm của TV. Trước khi gõ, xoá sạch ô tìm trên TV.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Gửi chữ có dấu", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(
                    if (s.keepAccents) "Tắt nếu TV hiện sai chữ có dấu"
                    else "Đang gửi không dấu (ô tìm YouTube không nhận dấu)",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = s.keepAccents, onCheckedChange = { vm.setKeepAccents(it) })
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = text,
            onValueChange = onText,
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            placeholder = { Text("Nhập nội dung cần tìm…") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = { vm.press(TvKey.ENTER) },
                onDone = { vm.press(TvKey.ENTER) },
                onGo = { vm.press(TvKey.ENTER) },
            ),
            trailingIcon = {
                if (text.isNotEmpty()) {
                    IconButton(onClick = { onText("") }) { Icon(Icons.Filled.Close, "Xoá hết") }
                }
            },
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KeyButton(Modifier.weight(1f).height(50.dp), repeat = true, onPress = {
                if (text.isNotEmpty()) onText(text.dropLast(1)) else vm.press(TvKey.DEL)
            }) { KeyIcon(Icons.AutoMirrored.Filled.Backspace, "Xoá") }
            KeyButton(Modifier.weight(1f).height(50.dp), onPress = {
                val clip = clipboard.getText()?.text
                if (!clip.isNullOrEmpty()) onText(text + clip)
            }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    KeyIcon(Icons.Filled.ContentPaste, "Dán", 18); Spacer(Modifier.width(6.dp)); KeyLabel("Dán")
                }
            }
            KeyButton(
                Modifier.weight(1f).height(50.dp),
                container = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                border = false,
                onPress = { vm.press(TvKey.ENTER) },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    KeyIcon(Icons.AutoMirrored.Filled.KeyboardReturn, "Enter", 18)
                    Spacer(Modifier.width(6.dp)); KeyLabel("Tìm")
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            "Điều hướng nhanh",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val k = Modifier.weight(1f).height(46.dp)
            KeyButton(k, repeat = true, onPress = { vm.press(TvKey.LEFT) }) {
                KeyIcon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Trái")
            }
            KeyButton(k, repeat = true, onPress = { vm.press(TvKey.UP) }) {
                KeyIcon(Icons.Filled.KeyboardArrowUp, "Lên")
            }
            KeyButton(k, onPress = { vm.press(TvKey.OK) }) { KeyLabel("OK") }
            KeyButton(k, repeat = true, onPress = { vm.press(TvKey.DOWN) }) {
                KeyIcon(Icons.Filled.KeyboardArrowDown, "Xuống")
            }
            KeyButton(k, repeat = true, onPress = { vm.press(TvKey.RIGHT) }) {
                KeyIcon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Phải")
            }
            KeyButton(k, onPress = { vm.press(TvKey.BACK) }) {
                KeyIcon(Icons.AutoMirrored.Filled.ArrowBack, "Quay lại")
            }
        }
        if (s.link != Link.CONNECTED) {
            Spacer(Modifier.height(12.dp))
            Text(
                if (s.irOnly || s.host == null)
                    "Gõ chữ cần kết nối Wi‑Fi với Google TV / Android TV. Vào ⚙ để chọn TV."
                else "Đang chờ kết nối Wi‑Fi với TV…",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

// ======================= Chọn TV =======================

@Composable
private fun SetupScreen(vm: MainViewModel, s: UiState) {
    val found by vm.discovery.found.collectAsStateWithLifecycle()
    var ip by rememberSaveable { mutableStateOf("") }
    var ipError by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (s.host != null || s.irOnly) {
                IconButton(onClick = { vm.closeSetup() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Quay lại") }
            }
            Text("Chọn TV Sony", fontSize = 22.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "TV phải là Google TV / Android TV và dùng chung mạng Wi‑Fi với điện thoại.",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("TV tìm thấy", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f))
            IconButton(onClick = { vm.discovery.start() }) { Icon(Icons.Filled.Refresh, "Tìm lại") }
        }
        if (found.isEmpty()) {
            Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Đang tìm TV trong mạng…", fontSize = 14.sp)
            }
        }
        found.forEach { tv ->
            Card(
                onClick = { vm.chooseTv(tv) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Tv, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(tv.name, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text(tv.host, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (tv.host == s.host) {
                        Text("Đang dùng", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))
        Text("Hoặc nhập địa chỉ IP của TV", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = ip,
                onValueChange = { ip = it.trim(); ipError = null },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("192.168.1.20") },
                isError = ipError != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = {
                    if (isIp(ip)) vm.chooseTv(FoundTv("TV Sony", ip)) else ipError = "Địa chỉ IP không hợp lệ"
                }),
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                if (isIp(ip)) vm.chooseTv(FoundTv("TV Sony", ip)) else ipError = "Địa chỉ IP không hợp lệ"
            }) { Text("Kết nối") }
        }
        val err = ipError
        if (err != null) Text(err, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        Text(
            "Xem IP trên TV: Cài đặt → Mạng và Internet → chọn mạng Wi‑Fi đang dùng.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )

        if (s.hasIr) {
            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = { vm.useIrOnly() }, modifier = Modifier.fillMaxWidth()) {
                Text("Chỉ dùng hồng ngoại (không gõ chữ được)")
            }
        }
    }
}

private fun isIp(s: String): Boolean {
    val parts = s.split('.')
    return parts.size == 4 && parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true }
}

// ======================= Ghép nối =======================

@Composable
private fun PairDialog(vm: MainViewModel, s: UiState) {
    var code by rememberSaveable { mutableStateOf("") }
    val err = s.pairError
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Ghép nối với TV") },
        text = {
            Column {
                if (s.pairStep == PairStep.STARTING) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Đang yêu cầu TV hiện mã…")
                    }
                } else {
                    Text("Nhập mã 6 ký tự đang hiện trên màn hình TV.")
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = code,
                        onValueChange = { v ->
                            code = v.uppercase().filter { it in '0'..'9' || it in 'A'..'F' }.take(6)
                        },
                        singleLine = true,
                        isError = err != null,
                        supportingText = if (err != null) {
                            { Text(err) }
                        } else null,
                        textStyle = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 24.sp,
                            letterSpacing = 6.sp,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters,
                            keyboardType = KeyboardType.Ascii,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { if (code.length == 6) vm.submitCode(code) }),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = s.pairStep == PairStep.WAITING_CODE && code.length == 6,
                onClick = { vm.submitCode(code) },
            ) { Text(if (s.pairStep == PairStep.CHECKING) "Đang kiểm tra…" else "Xác nhận") }
        },
        dismissButton = {
            Row {
                if (s.pairStep == PairStep.WAITING_CODE) {
                    TextButton(onClick = { code = ""; vm.startPairing() }) { Text("Mã mới") }
                }
                TextButton(onClick = { vm.cancelPairing() }) { Text("Huỷ") }
            }
        },
    )
}
