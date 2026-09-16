package dev.phonecam.app.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import dev.phonecam.app.ui.bridge.ViewfinderUiBridge

/**
 * Optional helper for custom chrome views: resolve bridge from activity.
 * Prefer constructor injection of [ViewfinderUiBridge] when possible.
 */
fun FrameLayout.findUiBridge(): ViewfinderUiBridge? {
    val a = context
    return (a as? android.app.Activity) as? ViewfinderUiBridge
}
