package com.privvy.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.privvy.app.R
import com.privvy.app.security.SessionGuardService
import kotlin.math.abs

/**
 * Floating "return to Privvy" pill shown over guest apps while the host is
 * backgrounded and the session is active. Requires the overlay permission;
 * without it the pill is silently skipped (Home offers an enable button).
 * The SYSTEM_ALERT_WINDOW grant also exempts Privvy from background-activity
 * launch restrictions, so tapping the pill can bring MainActivity forward.
 */
object ReturnPill {

    private var view: TextView? = null

    @Synchronized
    fun show(context: Context) {
        if (view != null || !Settings.canDrawOverlays(context)) return
        val app = context.applicationContext
        val manager = app.getSystemService(WindowManager::class.java) ?: return
        val density = app.resources.displayMetrics.density
        val pill = TextView(app).apply {
            text = "‹ Privvy"
            setTextColor(Color.WHITE)
            textSize = 14f
            val h = (14 * density).toInt()
            val v = (9 * density).toInt()
            setPadding(h, v, h, v)
            setBackgroundResource(R.drawable.return_pill_bg)
            elevation = 8 * density
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = (12 * density).toInt()
            y = (96 * density).toInt()
        }
        pill.setOnTouchListener(PillTouch(manager, pill, params) { returnToPrivvy(app) })
        runCatching { manager.addView(pill, params) }.onSuccess { view = pill }
    }

    @Synchronized
    fun hide(context: Context) {
        val current = view ?: return
        view = null
        runCatching {
            context.applicationContext.getSystemService(WindowManager::class.java)
                ?.removeViewImmediate(current)
        }
    }

    private fun returnToPrivvy(app: Context) {
        runCatching {
            ContextCompat.startForegroundService(
                app,
                Intent(app, SessionGuardService::class.java)
                    .setAction(SessionGuardService.ACTION_TOUCH),
            )
        }
        runCatching {
            app.startActivity(
                Intent(app, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                ),
            )
        }
    }

    private class PillTouch(
        private val manager: WindowManager,
        private val pill: TextView,
        private val params: WindowManager.LayoutParams,
        private val onTap: () -> Unit,
    ) : View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0
        private var dragged = false
        private val slop = ViewConfiguration.get(pill.context).scaledTouchSlop

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragged = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (abs(dx) > slop || abs(dy) > slop) dragged = true
                    if (dragged) {
                        // gravity is BOTTOM|END: x grows leftward, y upward.
                        params.x = (startX - dx).toInt().coerceAtLeast(0)
                        params.y = (startY - dy).toInt().coerceAtLeast(0)
                        runCatching { manager.updateViewLayout(pill, params) }
                    }
                }
                MotionEvent.ACTION_UP -> if (!dragged) onTap()
            }
            return true
        }
    }
}