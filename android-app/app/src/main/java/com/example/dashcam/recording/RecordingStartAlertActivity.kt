package com.example.dashcam.recording

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

object RecordingStartAlert {
    private val handler = Handler(Looper.getMainLooper())

    fun show(context: Context) {
        playTone()
        context.applicationContext.startActivity(
            Intent(context.applicationContext, RecordingStartAlertActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_HISTORY or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
            }
        )
    }

    private fun playTone() {
        val tone = try {
            ToneGenerator(AudioManager.STREAM_ALARM, 100)
        } catch (_: RuntimeException) {
            return
        }
        tone.startTone(ToneGenerator.TONE_PROP_BEEP, 260)
        handler.postDelayed({ tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 380) }, 340)
        handler.postDelayed({ tone.release() }, 900)
    }
}

class RecordingStartAlertActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var screenWakeLock: PowerManager.WakeLock? = null
    private val finishRunnable = Runnable { finishAlert() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        acquireScreenWakeLock()
        setContentView(buildContent())
        handler.postDelayed(finishRunnable, DISPLAY_DURATION_MS)
    }

    private fun buildContent() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(24), dp(24), dp(24), dp(24))
        setBackgroundColor(Color.rgb(5, 5, 5))

        addView(TextView(this@RecordingStartAlertActivity).apply {
            text = "●"
            textSize = 58f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(239, 68, 68))
        })
        addView(TextView(this@RecordingStartAlertActivity).apply {
            text = "开始录制"
            textSize = 38f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        })
        addView(TextView(this@RecordingStartAlertActivity).apply {
            text = "RECORDING STARTED"
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
            setTextColor(Color.rgb(167, 243, 208))
            letterSpacing = 0.12f
        })
    }

    @Suppress("DEPRECATION")
    private fun acquireScreenWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        screenWakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "$packageName:RecordingStartAlert"
        ).apply { acquire(DISPLAY_DURATION_MS + 1_000L) }
    }

    private fun finishAlert() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        screenWakeLock?.takeIf { it.isHeld }?.release()
        screenWakeLock = null
        finishAndRemoveTask()
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        handler.removeCallbacks(finishRunnable)
        screenWakeLock?.takeIf { it.isHeld }?.release()
        screenWakeLock = null
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val DISPLAY_DURATION_MS = 3_000L
    }
}
