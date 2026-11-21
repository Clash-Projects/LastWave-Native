package com.lastwave.app.ui.common

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View
import android.view.ViewParent
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

/** Keeps interactive content clear of side cutouts while its parent remains full-bleed. */
@Composable
fun Modifier.safeHorizontalContentPadding(): Modifier =
    windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))

/** Bottom clearance covering gesture navigation, three-button navigation, and cutouts. */
@Composable
fun safeDrawingBottomPadding(): Dp =
    WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()

/**
 * Ensures a Dialog or ModalBottomSheet window properly renders edge-to-edge
 * with transparent navigation and status bars, disabled contrast scrims,
 * and hardware blur-behind on supported Android versions (API 31+).
 */
@Composable
fun EdgeToEdgeDialogWindow(blurBehind: Boolean = true) {
    val view = LocalView.current
    DisposableEffect(view, blurBehind) {
        var dialogWindow: Window? = null
        var isDialog = false

        var current: ViewParent? = view.parent
        while (current != null) {
            if (current is DialogWindowProvider) {
                dialogWindow = current.window
                isDialog = true
                break
            }
            // NOTE: android.app.Dialog extends neither Context nor ViewParent,
            // so `is Dialog` checks are provably dead (compiler error) and a
            // Dialog never appears in a view-parent chain either. Dialog
            // windows are found via DialogWindowProvider above.
            current = current.parent
        }

        var activity: Activity? = null
        var actCtx: Context? = view.context
        while (actCtx is ContextWrapper) {
            if (actCtx is Activity) {
                activity = actCtx
                break
            }
            actCtx = actCtx.baseContext
        }
        if (activity == null) {
            var rootCtx: Context? = view.rootView.context
            while (rootCtx is ContextWrapper) {
                if (rootCtx is Activity) {
                    activity = rootCtx
                    break
                }
                rootCtx = rootCtx.baseContext
            }
        }

        val actDecor = activity?.window?.decorView
        if (dialogWindow == null && view.rootView != actDecor) {
            isDialog = true
        }

        val targetWindow = dialogWindow ?: activity?.window
        targetWindow?.let { w ->
            runCatching {
                WindowCompat.setDecorFitsSystemWindows(w, false)
                w.navigationBarColor = android.graphics.Color.TRANSPARENT
                w.statusBarColor = android.graphics.Color.TRANSPARENT
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    w.isNavigationBarContrastEnforced = false
                    w.isStatusBarContrastEnforced = false
                }
                if (isDialog) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val lp = w.attributes
                        if (blurBehind) {
                            w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                            lp.setBlurBehindRadius(120)
                            w.attributes = lp
                            runCatching { w.setBackgroundBlurRadius(120) }
                        } else {
                            w.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                            lp.setBlurBehindRadius(0)
                            w.attributes = lp
                            runCatching { w.setBackgroundBlurRadius(0) }
                        }
                    }
                    w.setDimAmount(if (blurBehind) 0.28f else 0.45f)
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val lp = view.rootView.layoutParams as? WindowManager.LayoutParams
            if (lp != null && view.rootView != actDecor) {
                runCatching {
                    if (blurBehind) {
                        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                        lp.setBlurBehindRadius(120)
                    } else {
                        lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
                        lp.setBlurBehindRadius(0)
                    }
                    val wm = view.context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                    wm?.updateViewLayout(view.rootView, lp)
                }
            }
        }

        var blurredView: View? = null
        if (blurBehind && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && activity != null) {
            val bgView = activity.findViewById<View>(android.R.id.content) ?: actDecor
            if (bgView != null && bgView != view.rootView) {
                runCatching {
                    bgView.setRenderEffect(
                        RenderEffect.createBlurEffect(32f, 32f, Shader.TileMode.CLAMP)
                    )
                    blurredView = bgView
                }
            }
        }

        onDispose {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching {
                    blurredView?.setRenderEffect(null)
                }
            }
        }
    }
}
