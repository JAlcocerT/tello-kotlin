package tech.jalcocer.tello

import android.app.Application
import android.net.Network
import android.view.Surface
import android.view.SurfaceView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tech.jalcocer.tello.core.Direction
import tech.jalcocer.tello.core.FlightUiState
import tech.jalcocer.tello.core.FlipDirection
import tech.jalcocer.tello.core.Telemetry
import tech.jalcocer.tello.network.TelloSession
import tech.jalcocer.tello.network.TelloWifiConnector
import tech.jalcocer.tello.video.MediaCapture
import tech.jalcocer.tello.video.TelloVideoPipeline

class TelloViewModel(application: Application) : AndroidViewModel(application), TelloSession.Callbacks {
    private val mutableState = MutableStateFlow(FlightUiState())
    val state: StateFlow<FlightUiState> = mutableState.asStateFlow()
    private val wifi = TelloWifiConnector(application)
    private val pipeline = TelloVideoPipeline(
        context = application,
        onStatus = ::onStatus,
        onVideoReady = { update { copy(videoReady = true, status = "Video feed ready") } },
    )
    private val session = TelloSession(pipeline, this)

    fun connect() {
        if (state.value.connecting || state.value.connected) return
        update { copy(connecting = true, status = "Select the TELLO Wi-Fi network…") }
        runCatching {
            wifi.request(
                onAvailable = ::connectNetwork,
                onUnavailable = { message ->
                    viewModelScope.launch(Dispatchers.IO) { session.disconnect(landIfFlying = false) }
                    update { copy(connecting = false, connected = false, status = message) }
                },
            )
        }.onFailure { error ->
            update { copy(connecting = false, status = "Wi-Fi request failed: ${error.message}") }
        }
    }

    private fun connectNetwork(network: Network) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { session.connect(network) }
                .onFailure { error -> update { copy(connecting = false, status = "Connection failed: ${error.message}") } }
        }
    }

    fun disconnect() {
        clearControls()
        viewModelScope.launch(Dispatchers.IO) {
            session.disconnect()
            wifi.release()
            update { FlightUiState(status = "Disconnected. UDP ports released.") }
        }
    }

    fun takeoff() = command { session.takeoff() }
    fun land() = command { session.land() }
    fun flip(direction: FlipDirection) = command { session.flip(direction) }

    fun toggleSpeed() {
        val fast = session.toggleSpeed()
        update { copy(fastMode = fast, status = if (fast) "Fast mode" else "Slow mode — hold to accelerate") }
    }

    fun press(direction: Direction) = session.press(direction)
    fun release(direction: Direction) = session.release(direction)
    fun clearControls() = session.clearControls()
    fun heartbeat() = session.heartbeat()
    fun setVideoSurface(surface: Surface?) = session.setSurface(surface)

    fun toggleRecording() {
        runCatching { session.toggleRecording() }.onFailure { onStatus(it.message ?: "Recording failed") }
    }

    fun savePhoto(view: SurfaceView?) {
        if (view == null || !state.value.videoReady) {
            onStatus("Wait for the video feed before taking a photo")
            return
        }
        viewModelScope.launch {
            runCatching { MediaCapture.savePhoto(getApplication(), view) }
                .onSuccess { onStatus("Photo saved: $it") }
                .onFailure { onStatus("Photo failed: ${it.message}") }
        }
    }

    fun onAppBackgrounded() {
        clearControls()
        if (state.value.flying) {
            onStatus("App left the foreground — landing for safety…")
            land()
        }
    }

    private fun command(block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { block() }.onFailure { onStatus(it.message ?: "Command failed") }
        }
    }

    override fun onStatus(message: String) = update { copy(status = message) }
    override fun onTelemetry(value: Telemetry) = update { copy(telemetry = mergeTelemetry(telemetry, value)) }
    override fun onConnected(value: Boolean) = update { copy(connected = value, connecting = false) }
    override fun onFlying(value: Boolean) = update { copy(flying = value) }
    override fun onRecording(value: Boolean) = update { copy(recording = value) }

    private fun update(transform: FlightUiState.() -> FlightUiState) {
        mutableState.update { current -> current.transform() }
    }

    private fun mergeTelemetry(old: Telemetry, next: Telemetry) = Telemetry(
        battery = next.battery ?: old.battery,
        heightCm = next.heightCm ?: old.heightCm,
        speedMps = next.speedMps ?: old.speedMps,
        temperatureC = next.temperatureC ?: old.temperatureC,
        wifiPercent = next.wifiPercent ?: old.wifiPercent,
    )

    override fun onCleared() {
        wifi.release()
        session.shutdownNow()
    }
}
