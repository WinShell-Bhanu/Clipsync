package com.bunty.clipsync

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class DiagnosticsViewModel : ViewModel() {
    private val _consoleLines = mutableStateListOf<ConsoleLine>()
    val consoleLines: List<ConsoleLine> = _consoleLines

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    private val _hasFailures = MutableStateFlow(false)
    val hasFailures: StateFlow<Boolean> = _hasFailures

    fun runDiagnostics(context: Context) {
        _consoleLines.clear()
        _hasFailures.value = false
        _isRunning.value = true

        viewModelScope.launch {
            ConnectionDiagnostics(context).runFullScanStream().collect { line ->
                _consoleLines.add(line)
                if (line is ConsoleLine.Result && line.status == DiagnosticResult.Status.FAIL) {
                    _hasFailures.value = true
                }
                if (line is ConsoleLine.Summary) {
                    _isRunning.value = false
                }
            }
        }
    }
}

@Composable
fun DiagnosticConsoleScreen(
    viewModel: DiagnosticsViewModel = viewModel(),
    onContinueToRepair: () -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val lines = viewModel.consoleLines
    val isRunning by viewModel.isRunning.collectAsState()
    val hasFailures by viewModel.hasFailures.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        viewModel.runDiagnostics(context)
    }

    // Auto-scroll to bottom as new lines appear
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF0D1117)).systemBarsPadding()) {

        // Fake terminal title bar
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row {
                Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFFFF5F56)))
                Spacer(Modifier.width(6.dp))
                Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFFFFBD2E)))
                Spacer(Modifier.width(6.dp))
                Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFF27C93F)))
            }
            Spacer(Modifier.width(12.dp))
            Text("Diagnostic Console", color = Color.Gray, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
        }

        HorizontalDivider(color = Color(0xFF30363D))

        // Scrolling console output
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(12.dp)
        ) {
            items(lines) { line ->
                when (line) {
                    is ConsoleLine.Command -> Text(
                        "> ${line.text}",
                        color = Color(0xFF58A6FF),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                    is ConsoleLine.Result -> Text(
                        "  ${statusSymbol(line.status)} ${line.text}",
                        color = statusColor(line.status),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 2.dp, horizontal = 8.dp)
                    )
                    ConsoleLine.Summary -> Text(
                        "\n> Scan complete.",
                        color = Color.Gray,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp
                    )
                }
            }

            if (isRunning) {
                item {
                    Text("_", color = Color.White, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.alpha(if ((System.currentTimeMillis() / 500) % 2 == 0L) 1f else 0f))
                }
            }
        }

        HorizontalDivider(color = Color(0xFF30363D))

        // Bottom actions
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onCancel) {
                Text("Cancel", color = Color.Gray, fontFamily = FontFamily.Monospace)
            }

            if (!isRunning) {
                TextButton(onClick = {
                    val logText = lines.joinToString("\n") { 
                        when (it) {
                            is ConsoleLine.Command -> "> ${it.text}"
                            is ConsoleLine.Result -> "  ${statusSymbol(it.status)} ${it.text}"
                            ConsoleLine.Summary -> "> Scan complete."
                        }
                    }
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText("Diagnostics Log", logText)
                    clipboard.setPrimaryClip(clip)
                    android.widget.Toast.makeText(context, "Log copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
                }) {
                    Text("Copy Log", color = Color(0xFF58A6FF), fontFamily = FontFamily.Monospace)
                }
            }

            val hasPermissionFailures = lines.any { it is ConsoleLine.Result && it.status == DiagnosticResult.Status.FAIL && it.text.contains("Permission", ignoreCase = true) }

            Button(
                onClick = {
                    if (hasPermissionFailures) {
                        val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = android.net.Uri.parse("package:${context.packageName}")
                        }
                        context.startActivity(intent)
                    } else {
                        onContinueToRepair()
                    }
                },
                enabled = !isRunning,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (hasFailures) Color(0xFFDA3633) else Color(0xFF238636)
                )
            ) {
                Text(
                    if (isRunning) "Scanning..." 
                    else if (hasPermissionFailures) "Repair Permissions"
                    else if (hasFailures) "Continue Anyway" 
                    else "Continue to Re-pair",
                    fontFamily = FontFamily.Monospace,
                    color = Color.White
                )
            }
        }
    }
}

fun statusSymbol(status: DiagnosticResult.Status) = when (status) {
    DiagnosticResult.Status.PASS -> "✓"
    DiagnosticResult.Status.WARN -> "⚠"
    DiagnosticResult.Status.FAIL -> "✗"
}

fun statusColor(status: DiagnosticResult.Status) = when (status) {
    DiagnosticResult.Status.PASS -> Color(0xFF3FB950)
    DiagnosticResult.Status.WARN -> Color(0xFFD29922)
    DiagnosticResult.Status.FAIL -> Color(0xFFF85149)
}
