package dev.phonecam.app.ui.bridge

/**
 * Optional event sink for one-off UI effects (toasts, navigation).
 * Implemented by Activity; UI widgets may only depend on this interface.
 */
interface UiHost {
    fun showToast(message: String)
    fun onChromeVisibilityChanged(visible: Boolean)
}
