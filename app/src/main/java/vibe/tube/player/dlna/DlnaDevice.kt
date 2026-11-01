package vibe.tube.player.dlna

data class DlnaDevice(
    val friendlyName: String,
    val location: String,
    val usn: String,

    val avTransportUrl: String = ""
)
