package dev.phonecam.app.ui.bridge

/**
 * Immutable snapshot the viewfinder chrome renders.
 * UI must not reach into trackers or sockets; read this only.
 */
data class ViewfinderUiState(
    val statusText: String = "未连接",
    val online: Boolean = false,
    val streaming: Boolean = false,
    val useAr: Boolean = false,
    val arTracking: Boolean = false,
    val showDetails: Boolean = false,
    val poseHud: String = "",
    val zoom: Float = 1f,
    val zoomMin: Float = 0.25f,
    val zoomMax: Float = 4f,
    val presets: List<Float> = listOf(0.6f, 1f, 2f),
    val blurOn: Boolean = true,
    val blurFps: Int = 60,
    val blurRes: Int = 2,
    val host: String = "",
    val port: Int = 42424,
    val bitrateMbps: Int = 12,
)

/**
 * UI → feature commands. Implemented by MainActivity (or a controller).
 * Phone UI agent: only call these; never construct UDP/TCP/AR yourself.
 */
interface ViewfinderUiBridge {
    fun currentState(): ViewfinderUiState

    fun setStreaming(on: Boolean)
    fun setModeAr(on: Boolean)
    fun calibrate()
    fun openSettings(open: Boolean)
    fun openBlurCard(open: Boolean)
    fun openFullscreenViewfinder()

    fun setZoom(value: Float, fromUser: Boolean)
    fun setZoomPreset(index: Int)
    fun setZoomRange(min: Float, max: Float)
    fun addZoomPreset(value: Float)
    fun removeZoomPreset(index: Int)

    fun setDetailsVisible(on: Boolean)
    fun setBlurEnabled(on: Boolean)
    fun setBlurFps(fps: Int)
    fun setBlurRes(resLevel: Int)

    fun setHost(host: String)
    fun setPosePort(port: Int)
    fun setBitrate(mbps: Int)
    fun setBottomUiScale(scale: Float)

    fun setCropMode(landscape: Boolean)
    fun setLandscapeFlip(flip: Boolean)
    fun setFrameAr(ar: Float)

    fun openArDebug()
}
