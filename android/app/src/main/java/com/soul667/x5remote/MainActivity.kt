package com.soul667.x5remote

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                AppScreen()
            }
        }
    }
}

private val Green = Color(0xFF18A058)
private val Red = Color(0xFFD03050)
private val Dim = Color(0xFF999999)
private val Warn = Color(0xFFFFD666)

@Composable
fun AppScreen() {
    val context = LocalContext.current
    val controller = remember { X5Controller(context.applicationContext) }
    val scope = rememberCoroutineScope()

    val found by controller.found.collectAsState()
    val cams by controller.cameras.collectAsState()
    val scanning by controller.scanning.collectAsState()
    val connecting by controller.connecting.collectAsState()
    val recording by controller.recording.collectAsState()
    val busy by controller.busy.collectAsState()
    val results by controller.lastResults.collectAsState()
    val lastAction by controller.lastAction.collectAsState()
    val clockNote by controller.clockNote.collectAsState()
    val message by controller.message.collectAsState()

    var selected by remember { mutableStateOf(setOf<String>()) }
    var delaySec by remember { mutableStateOf("0") }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val perms = remember {
        if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) pendingAction?.invoke()
        pendingAction = null
    }

    fun ensurePerms(action: () -> Unit) {
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            action()
        } else {
            pendingAction = action
            launcher.launch(missing.toTypedArray())
        }
    }

    val readyCount = cams.count { it.ready }
    val delayMs = (delaySec.toLongOrNull() ?: 0L) * 1000

    fun fire(name: String, ms: Long, action: suspend (BleCamera) -> CommandResult) {
        scope.launch { controller.broadcast(name, ms, action) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("X5 双机遥控", fontSize = 22.sp, fontWeight = FontWeight.Bold)

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { ensurePerms { controller.startScan() } },
                enabled = !scanning,
                modifier = Modifier.weight(1f),
            ) { Text(if (scanning) "扫描中…" else "扫描相机") }
            Button(
                onClick = {
                    val devices = found.filter { it.address in selected }
                    ensurePerms { controller.connect(devices) }
                },
                enabled = selected.isNotEmpty() && !connecting,
                modifier = Modifier.weight(1f),
            ) { Text(if (connecting) "连接中…" else "连接所选 (${selected.size})") }
        }

        message?.let { Text(it, fontSize = 13.sp, color = Dim) }

        if (found.isNotEmpty()) {
            Text("发现的相机", fontWeight = FontWeight.SemiBold)
            found.forEach { fd ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = fd.address in selected,
                        onCheckedChange = { checked ->
                            selected =
                                if (checked) selected + fd.address else selected - fd.address
                        },
                    )
                    Column(Modifier.weight(1f)) {
                        Text(fd.name, fontWeight = FontWeight.Medium)
                        Text(
                            "${fd.address}  RSSI ${fd.rssi}",
                            fontSize = 12.sp,
                            color = Dim,
                        )
                    }
                }
            }
        }

        if (cams.isNotEmpty()) {
            Text("已连接相机", fontWeight = FontWeight.SemiBold)
            cams.forEach { cam ->
                val state = when {
                    cam.ready -> "就绪"
                    cam.lastError != null -> "错误: ${cam.lastError}"
                    else -> "连接中…"
                }
                Text(
                    "• ${cam.label}  $state",
                    fontSize = 13.sp,
                    color = if (cam.ready) Green else Color(0xFFE88080),
                )
            }
        }

        HorizontalDivider()

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    fire("record start", delayMs) {
                        it.sendCommand(X5Protocol.CMD_START_CAPTURE)
                    }
                },
                enabled = !busy && !recording && readyCount > 0,
                colors = ButtonDefaults.buttonColors(containerColor = Green),
                modifier = Modifier
                    .weight(1f)
                    .height(64.dp),
            ) { Text("● 开始录制", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
            Button(
                onClick = {
                    fire("record stop", 0) {
                        it.sendCommand(X5Protocol.CMD_STOP_CAPTURE, timeoutMs = 12_000)
                    }
                },
                enabled = !busy && readyCount > 0,
                colors = ButtonDefaults.buttonColors(containerColor = Red),
                modifier = Modifier
                    .weight(1f)
                    .height(64.dp),
            ) { Text("■ 停止录制", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = delaySec,
                onValueChange = { delaySec = it.filter { c -> c.isDigit() }.take(2) },
                label = { Text("启动延迟(秒)") },
                singleLine = true,
                modifier = Modifier.width(140.dp),
            )
            Button(
                onClick = {
                    fire("photo", 0) { it.sendCommand(X5Protocol.CMD_TAKE_PHOTO) }
                },
                enabled = !busy && readyCount > 0,
                modifier = Modifier.weight(1f),
            ) { Text("📷 拍照") }
            Button(
                onClick = {
                    fire("marker", 0) { it.sendCommand(X5Protocol.CMD_SET_HIGHLIGHT) }
                },
                enabled = !busy && readyCount > 0,
                modifier = Modifier.weight(1f),
            ) { Text("🚩 标记") }
        }

        if (busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        if (results.isNotEmpty()) {
            Text("最近指令：${lastAction ?: ""}", fontWeight = FontWeight.SemiBold)
            results.forEach { r ->
                Column {
                    Text(
                        "• ${r.label}  派发 +%.2f ms  RTT %.1f ms  %s".format(
                            r.dispatchMillis,
                            r.rttMillis,
                            if (r.ok) "✓" else "✗ ${r.error ?: ""}",
                        ),
                        fontSize = 13.sp,
                        color = if (r.ok) Green else Color(0xFFE88080),
                    )
                    r.file?.let { Text("    $it", fontSize = 12.sp, color = Dim) }
                }
            }
            clockNote?.let {
                Text("⏱ $it", color = Warn, fontWeight = FontWeight.Bold)
            }
        }

        HorizontalDivider()

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = { controller.disconnectAll() },
                enabled = cams.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) { Text("断开全部") }
        }

        Text(
            "提示：两台相机需开机且未被手机占用；相机无操作数分钟会自动关机。",
            fontSize = 12.sp,
            color = Color(0xFF888888),
        )
    }
}
