package tech.jalcocer.tello.network

import android.net.Network
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tech.jalcocer.tello.core.FlipDirection
import tech.jalcocer.tello.core.RcController
import tech.jalcocer.tello.core.Telemetry
import tech.jalcocer.tello.core.parseTelemetry
import tech.jalcocer.tello.video.TelloVideoPipeline
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

class TelloSession(
    private val video: TelloVideoPipeline,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onStatus(message: String)
        fun onTelemetry(value: Telemetry)
        fun onConnected(value: Boolean)
        fun onFlying(value: Boolean)
        fun onRecording(value: Boolean)
    }

    private var scope: CoroutineScope? = null
    private var commandSocket: DatagramSocket? = null
    private var telemetrySocket: DatagramSocket? = null
    private var videoSocket: DatagramSocket? = null
    private val commandMutex = Mutex()
    private val rc = RcController()
    private var rcJob: Job? = null
    @Volatile private var running = false
    @Volatile private var flying = false
    @Volatile var fastMode = false
        private set

    suspend fun connect(network: Network) {
        disconnect(landIfFlying = false)
        callbacks.onStatus("Opening Tello UDP sockets…")
        val nextScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = nextScope
        try {
            commandSocket = boundSocket(network, 0).apply {
                soTimeout = 4_000
                connect(TELLO_ADDRESS)
            }
            telemetrySocket = boundSocket(network, TELEMETRY_PORT).apply { soTimeout = 500 }
            videoSocket = boundSocket(network, VIDEO_PORT).apply { soTimeout = 500 }
            running = true
            startTelemetry(nextScope)
            startVideo(nextScope)
            commandRetry("command", 3)
            val battery = commandRetry("battery?", 3).trim().toIntOrNull()
                ?: error("Tello returned an invalid battery value")
            callbacks.onTelemetry(Telemetry(battery = battery))
            if (battery < BATTERY_MINIMUM) error("Battery is $battery%; charge above $BATTERY_MINIMUM% before flying")
            startRcLoop(nextScope)
            commandRetry("streamon", 3)
            callbacks.onConnected(true)
            callbacks.onStatus(if (battery < BATTERY_WARNING) "Connected — low battery ($battery%)" else "Connected. Ready to fly.")
        } catch (error: Throwable) {
            disconnect(landIfFlying = false)
            throw error
        }
    }

    suspend fun disconnect(landIfFlying: Boolean = true) {
        if (landIfFlying && flying) runCatching { land() }
        if (running) runCatching { commandOnce("streamoff", 2_000) }
        running = false
        rc.clear()
        rcJob?.cancel()
        rcJob = null
        commandSocket?.close()
        telemetrySocket?.close()
        videoSocket?.close()
        commandSocket = null
        telemetrySocket = null
        videoSocket = null
        scope?.cancel()
        scope = null
        flying = false
        video.reset()
        callbacks.onRecording(false)
        callbacks.onFlying(false)
        callbacks.onConnected(false)
    }

    /** Best-effort, non-blocking safety shutdown for ViewModel/process teardown. */
    fun shutdownNow() {
        rc.clear()
        val socket = commandSocket
        fun sendWithoutWaiting(text: String) {
            val bytes = text.toByteArray()
            runCatching { socket?.send(DatagramPacket(bytes, bytes.size)) }
        }
        if (flying) {
            sendWithoutWaiting("rc 0 0 0 0")
            sendWithoutWaiting("land")
        }
        sendWithoutWaiting("streamoff")
        running = false
        flying = false
        rcJob?.cancel()
        commandSocket?.close()
        telemetrySocket?.close()
        videoSocket?.close()
        scope?.cancel()
        commandSocket = null
        telemetrySocket = null
        videoSocket = null
        scope = null
        video.reset()
    }

    suspend fun takeoff() {
        callbacks.onStatus("Taking off…")
        try {
            commandOnce("takeoff", 15_000)
            flying = true
            callbacks.onFlying(true)
            callbacks.onStatus("Flying")
        } catch (error: Throwable) {
            flying = true
            callbacks.onFlying(true)
            throw IllegalStateException("Takeoff state uncertain; land if airborne: ${error.message}")
        }
    }

    suspend fun land() {
        if (!flying) return
        sendRc(intArrayOf(0, 0, 0, 0))
        callbacks.onStatus("Landing…")
        commandOnce("land", 15_000)
        flying = false
        rc.clear()
        callbacks.onFlying(false)
        callbacks.onStatus("Landed")
    }

    suspend fun flip(direction: FlipDirection) {
        check(flying) { "Take off before flipping" }
        commandOnce("flip ${direction.command}", 7_000)
        callbacks.onStatus("Flip ${direction.name.lowercase()} complete")
    }

    fun toggleSpeed(): Boolean {
        fastMode = !fastMode
        return fastMode
    }

    fun press(direction: tech.jalcocer.tello.core.Direction) = rc.press(direction)
    fun release(direction: tech.jalcocer.tello.core.Direction) = rc.release(direction)
    fun clearControls() = rc.clear()
    fun heartbeat() = rc.heartbeat()
    fun setSurface(surface: Surface?) = video.setSurface(surface)

    fun toggleRecording(): Boolean {
        check(running) { "Connect to the Tello first" }
        val value = video.toggleRecording()
        callbacks.onRecording(value)
        callbacks.onStatus(if (value) "Recording requested — waiting for video" else "Recording saved")
        return value
    }

    private fun startTelemetry(activeScope: CoroutineScope) {
        val socket = telemetrySocket ?: return
        activeScope.launch {
            val buffer = ByteArray(2_048)
            while (isActive && running) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    callbacks.onTelemetry(parseTelemetry(String(packet.data, packet.offset, packet.length)))
                } catch (_: SocketTimeoutException) {
                    // Allows cancellation checks.
                } catch (error: Throwable) {
                    if (running) callbacks.onStatus("Telemetry stopped: ${error.message}")
                    break
                }
            }
        }
    }

    private fun startVideo(activeScope: CoroutineScope) {
        val socket = videoSocket ?: return
        activeScope.launch {
            val buffer = ByteArray(4_096)
            var firstPacket = true
            while (isActive && running) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    if (firstPacket) {
                        firstPacket = false
                        callbacks.onStatus("Video packets received — reading H.264 headers…")
                    }
                    video.accept(packet.data, packet.length)
                } catch (_: SocketTimeoutException) {
                    // Allows cancellation checks.
                } catch (error: Throwable) {
                    if (running) callbacks.onStatus("Video stopped: ${error.message}")
                    break
                }
            }
        }
    }

    private fun startRcLoop(activeScope: CoroutineScope) {
        rcJob = activeScope.launch {
            while (isActive && running) {
                delay(50)
                if (flying) sendRc(rc.values(fastMode))
            }
        }
    }

    private suspend fun sendRc(values: IntArray) {
        if (!commandMutex.tryLock()) return
        try {
            commandSocket?.send(DatagramPacket(
                "rc ${values[0]} ${values[1]} ${values[2]} ${values[3]}".toByteArray(),
                "rc ${values[0]} ${values[1]} ${values[2]} ${values[3]}".length,
            ))
        } finally {
            commandMutex.unlock()
        }
    }

    private suspend fun commandRetry(command: String, attempts: Int): String {
        var last: Throwable = IllegalStateException("No response")
        repeat(attempts) { attempt ->
            try {
                return commandOnce(command, 4_000)
            } catch (error: Throwable) {
                last = error
                if (attempt + 1 < attempts) {
                    callbacks.onStatus("No response to $command — retrying (${attempt + 2}/$attempts)…")
                    delay(250)
                }
            }
        }
        throw last
    }

    private suspend fun commandOnce(command: String, timeoutMs: Int): String = commandMutex.withLock {
        val socket = commandSocket ?: error("Connect to the Tello first")
        socket.soTimeout = timeoutMs
        val bytes = command.toByteArray()
        socket.send(DatagramPacket(bytes, bytes.size))
        val responseBytes = ByteArray(1_024)
        val response = DatagramPacket(responseBytes, responseBytes.size)
        socket.receive(response)
        String(response.data, response.offset, response.length).trim().lowercase().also {
            if (it.startsWith("error")) error(it)
        }
    }

    private fun boundSocket(network: Network, port: Int): DatagramSocket = DatagramSocket(null).apply {
        reuseAddress = true
        network.bindSocket(this)
        bind(InetSocketAddress("0.0.0.0", port))
    }

    companion object {
        private val TELLO_ADDRESS = InetSocketAddress("192.168.10.1", 8889)
        private const val TELEMETRY_PORT = 8890
        private const val VIDEO_PORT = 11111
        private const val BATTERY_MINIMUM = 20
        private const val BATTERY_WARNING = 30
    }
}
