package tech.jalcocer.tello.core

data class Telemetry(
    val battery: Int? = null,
    val heightCm: Int? = null,
    val speedMps: Double? = null,
    val temperatureC: Int? = null,
    val wifiPercent: Int? = null,
)

data class FlightUiState(
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val flying: Boolean = false,
    val fastMode: Boolean = false,
    val recording: Boolean = false,
    val videoReady: Boolean = false,
    val status: String = "Connect to the TELLO Wi-Fi to begin",
    val telemetry: Telemetry = Telemetry(),
)

enum class Direction {
    LEFT, RIGHT, FORWARD, BACK, UP, DOWN, YAW_LEFT, YAW_RIGHT,
}

enum class FlipDirection(val command: String) {
    LEFT("l"), RIGHT("r"), FORWARD("f"), BACK("b"),
}

fun parseTelemetry(packet: String): Telemetry {
    val fields = packet.trim().split(';').mapNotNull { field ->
        val split = field.split(':', limit = 2)
        if (split.size != 2) null else split[1].toDoubleOrNull()?.let { split[0] to it }
    }.toMap()
    val low = fields["templ"] ?: 0.0
    val high = fields["temph"] ?: low
    val vgx = fields["vgx"] ?: 0.0
    val vgy = fields["vgy"] ?: 0.0
    return Telemetry(
        battery = fields["bat"]?.toInt(),
        heightCm = fields["h"]?.toInt(),
        speedMps = kotlin.math.round(kotlin.math.hypot(vgx, vgy)) / 10.0,
        temperatureC = ((low + high) / 2.0).toInt(),
        wifiPercent = fields["wifi"]?.toInt(),
    )
}
