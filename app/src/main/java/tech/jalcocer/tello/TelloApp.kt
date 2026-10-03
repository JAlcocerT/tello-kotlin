package tech.jalcocer.tello

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import tech.jalcocer.tello.core.Direction
import tech.jalcocer.tello.core.FlightUiState
import tech.jalcocer.tello.core.FlipDirection

private val Night = Color(0xFF071019)
private val Panel = Color(0xFF0C1922)
private val PanelRaised = Color(0xFF10232C)
private val Line = Color(0xFF203943)
private val Cyan = Color(0xFF51E1CD)
private val Green = Color(0xFF65E69F)
private val Red = Color(0xFFFF656D)
private val Amber = Color(0xFFFFD166)
private val Muted = Color(0xFF8DA1A8)

@Composable
fun TelloApp(model: TelloViewModel, onConnect: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    var videoView by remember { mutableStateOf<SurfaceView?>(null) }

    LaunchedEffect(state.connected) {
        while (state.connected) {
            delay(250)
            model.heartbeat()
        }
    }

    MaterialTheme {
        Column(Modifier.fillMaxSize().background(Night).padding(12.dp)) {
            TopBar(state, onConnect, model::disconnect)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                VideoPanel(
                    state = state,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onViewReady = { videoView = it },
                    onSurface = model::setVideoSurface,
                )
                ControlPanel(
                    state = state,
                    model = model,
                    savePhoto = { model.savePhoto(videoView) },
                    modifier = Modifier.width(300.dp).fillMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun TopBar(state: FlightUiState, onConnect: () -> Unit, onDisconnect: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text("◆ TELLO", color = Cyan, fontWeight = FontWeight.Black, fontSize = 18.sp)
        Text("  NATIVE ANDROID", color = Muted, fontWeight = FontWeight.Bold, fontSize = 9.sp)
        Spacer(Modifier.width(20.dp))
        Text(
            if (state.connected) "● CONNECTED" else "● OFFLINE",
            color = if (state.connected) Green else Muted,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
        )
        Text(
            state.status,
            color = Muted,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
        )
        ActionButton(
            text = if (state.connecting) "WORKING…" else if (state.connected) "DISCONNECT" else "CONNECT",
            enabled = !state.connecting,
            danger = state.connected,
            onClick = if (state.connected) onDisconnect else onConnect,
        )
    }
}

@Composable
private fun VideoPanel(
    state: FlightUiState,
    modifier: Modifier,
    onViewReady: (SurfaceView) -> Unit,
    onSurface: (android.view.Surface?) -> Unit,
) {
    Box(modifier.background(Color.Black).border(1.dp, Line, RoundedCornerShape(4.dp))) {
        AndroidView(
            factory = { context ->
                SurfaceView(context).also { view ->
                    onViewReady(view)
                    view.holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) = onSurface(holder.surface)
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = onSurface(holder.surface)
                        override fun surfaceDestroyed(holder: SurfaceHolder) = onSurface(null)
                    })
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (!state.videoReady) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("◎", color = Color(0x6651E1CD), fontSize = 52.sp)
                Text("WAITING FOR CAMERA FEED", color = Muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                Text("Native MediaCodec · UDP 11111", color = Color(0xFF465D65), fontFamily = FontFamily.Monospace, fontSize = 9.sp)
            }
        }
        Reticle(Modifier.align(Alignment.Center).size(52.dp))
        Hud(if (state.flying) "FLYING" else "LANDED", if (state.flying) Green else Muted, Modifier.align(Alignment.TopStart))
        Hud("BAT ${state.telemetry.battery ?: "--"}%", batteryColor(state.telemetry.battery), Modifier.align(Alignment.TopEnd))
        Hud(if (state.fastMode) "FAST" else "SLOW · HOLD TO ACCELERATE", Cyan, Modifier.align(Alignment.BottomStart))
        Hud("H ${state.telemetry.heightCm ?: "--"} cm", Color.White, Modifier.align(Alignment.BottomEnd))
        if (state.recording) Hud("● REC", Red, Modifier.align(Alignment.TopStart).padding(top = 34.dp))
    }
}

@Composable
private fun Reticle(modifier: Modifier) {
    Canvas(modifier) {
        drawCircle(Cyan.copy(alpha = .45f), style = Stroke(width = 1.dp.toPx()))
        drawLine(Cyan.copy(alpha = .65f), start = center.copy(x = 0f), end = center.copy(x = size.width), strokeWidth = 1.dp.toPx(), cap = StrokeCap.Round)
        drawLine(Cyan.copy(alpha = .65f), start = center.copy(y = 0f), end = center.copy(y = size.height), strokeWidth = 1.dp.toPx(), cap = StrokeCap.Round)
    }
}

@Composable
private fun Hud(text: String, color: Color, modifier: Modifier) {
    Text(
        text,
        color = color,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 10.sp,
        modifier = modifier.padding(14.dp).background(Night.copy(alpha = .75f)).border(width = 1.dp, color = color.copy(alpha = .5f)).padding(6.dp),
    )
}

@Composable
private fun ControlPanel(
    state: FlightUiState,
    model: TelloViewModel,
    savePhoto: () -> Unit,
    modifier: Modifier,
) {
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Card("01 / FLIGHT STATUS") {
            Metric("BATTERY", "${state.telemetry.battery ?: "--"}%", batteryColor(state.telemetry.battery))
            Metric("HEIGHT", "${state.telemetry.heightCm ?: "--"} cm")
            Metric("SPEED", state.telemetry.speedMps?.let { "%.1f m/s".format(it) } ?: "-- m/s")
            Metric("TEMP", "${state.telemetry.temperatureC ?: "--"} °C")
            Metric("WI-FI", "${state.telemetry.wifiPercent ?: "--"}%")
        }
        Card("02 / FLIGHT") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionButton("TAKE OFF", state.connected && !state.flying, modifier = Modifier.weight(1f), onClick = model::takeoff)
                ActionButton("LAND", state.connected && state.flying, modifier = Modifier.weight(1f), onClick = model::land)
            }
            ActionButton("EMERGENCY LAND", state.connected && state.flying, danger = true, fill = true, onClick = model::land)
            ActionButton("MODE: ${if (state.fastMode) "FAST" else "SLOW"}", state.connected, fill = true, onClick = model::toggleSpeed)
        }
        Card("03 / MOVEMENT") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                DirectionPad(
                    top = "▲", left = "◀", right = "▶", bottom = "▼",
                    enabled = state.connected && state.flying,
                    directions = listOf(Direction.FORWARD, Direction.LEFT, Direction.RIGHT, Direction.BACK),
                    onPress = model::press, onRelease = model::release, onStop = model::clearControls,
                )
                DirectionPad(
                    top = "+H", left = "↶", right = "↷", bottom = "−H",
                    enabled = state.connected && state.flying,
                    directions = listOf(Direction.UP, Direction.YAW_LEFT, Direction.YAW_RIGHT, Direction.DOWN),
                    onPress = model::press, onRelease = model::release, onStop = model::clearControls,
                )
            }
            Text("MOVE                 ALTITUDE / YAW", color = Muted, fontSize = 8.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
        Card("04 / FLIPS") {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                FlipDirection.entries.forEach { direction ->
                    ActionButton(direction.name.take(1), state.connected && state.flying, modifier = Modifier.weight(1f)) { model.flip(direction) }
                }
            }
        }
        Card("05 / CAMERA") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionButton("PHOTO", state.connected && state.videoReady, modifier = Modifier.weight(1f), onClick = savePhoto)
                ActionButton(if (state.recording) "STOP REC" else "RECORD", state.connected && state.videoReady, danger = state.recording, modifier = Modifier.weight(1f), onClick = model::toggleRecording)
            }
        }
    }
}

@Composable
private fun DirectionPad(
    top: String,
    left: String,
    right: String,
    bottom: String,
    enabled: Boolean,
    directions: List<Direction>,
    onPress: (Direction) -> Unit,
    onRelease: (Direction) -> Unit,
    onStop: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        HoldButton(top, enabled, directions[0], onPress, onRelease)
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            HoldButton(left, enabled, directions[1], onPress, onRelease)
            ActionButton("■", enabled, danger = true, compact = true, onClick = onStop)
            HoldButton(right, enabled, directions[2], onPress, onRelease)
        }
        HoldButton(bottom, enabled, directions[3], onPress, onRelease)
    }
}

@Composable
private fun HoldButton(
    text: String,
    enabled: Boolean,
    direction: Direction,
    onPress: (Direction) -> Unit,
    onRelease: (Direction) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    LaunchedEffect(pressed, enabled) {
        if (pressed && enabled) onPress(direction) else onRelease(direction)
    }
    ActionButton(text, enabled, compact = true, interactionSource = interaction, onClick = {})
    DisposableEffect(Unit) { onDispose { onRelease(direction) } }
}

@Composable
private fun Card(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(4.dp)).border(1.dp, Line, RoundedCornerShape(4.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, color = Cyan, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black, fontSize = 9.sp)
        content()
    }
}

@Composable
private fun Metric(label: String, value: String, color: Color = Color.White) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Muted, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
        Text(value, color = color, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp)
    }
}

@Composable
private fun ActionButton(
    text: String,
    enabled: Boolean = true,
    danger: Boolean = false,
    fill: Boolean = false,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource? = null,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        shape = RoundedCornerShape(3.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (danger) Color(0xFF51232A) else PanelRaised,
            contentColor = if (danger) Color(0xFFFFBEC0) else Color.White,
            disabledContainerColor = Color(0xFF0D1A21),
            disabledContentColor = Color(0xFF4D6268),
        ),
        modifier = modifier.then(if (fill) Modifier.fillMaxWidth() else Modifier).then(if (compact) Modifier.size(38.dp, 32.dp) else Modifier.height(36.dp)),
        contentPadding = if (compact) androidx.compose.foundation.layout.PaddingValues(0.dp) else ButtonDefaults.ContentPadding,
    ) {
        Text(text, fontSize = if (compact) 11.sp else 9.sp, fontWeight = FontWeight.Black, maxLines = 1)
    }
}

private fun batteryColor(value: Int?): Color = when {
    value == null -> Muted
    value <= 20 -> Red
    value <= 50 -> Amber
    else -> Green
}
