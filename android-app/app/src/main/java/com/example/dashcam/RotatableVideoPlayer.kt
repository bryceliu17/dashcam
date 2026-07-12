package com.example.dashcam

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

class RotatableVideoPlayer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs), TextureView.SurfaceTextureListener {
    private val textureView = TextureView(context)
    private val playPauseButton = Button(context).apply { text = "Pause"; isAllCaps = false }
    private val timeView = TextView(context).apply {
        text = "00:00 / 00:00"
        gravity = Gravity.CENTER
        setTextColor(0xfff3f4f6.toInt())
    }
    private val seekBar = SeekBar(context)
    private val fullscreenButton = Button(context).apply { text = "Fullscreen"; isAllCaps = false }
    private val controlBar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), 0, dp(4), 0)
        setBackgroundColor(0xcc111827.toInt())
    }
    private val handler = Handler(Looper.getMainLooper())
    private var mediaPlayer: MediaPlayer? = null
    private var videoPath: String? = null
    private var rotationDegrees = 0
    private var startWhenPrepared = false
    private var isPrepared = false
    private var videoWidth = 0
    private var videoHeight = 0
    private var isFullscreen = false
    private var fullscreenListener: ((Boolean) -> Unit)? = null
    private var userSeeking = false
    private val progressUpdater = object : Runnable {
        override fun run() {
            updateControls()
            if (mediaPlayer != null) handler.postDelayed(this, 500)
        }
    }

    init {
        addView(textureView)
        textureView.surfaceTextureListener = this
        controlBar.addView(playPauseButton, LinearLayout.LayoutParams(dp(76), dp(44)))
        controlBar.addView(timeView, LinearLayout.LayoutParams(dp(105), dp(44)))
        controlBar.addView(seekBar, LinearLayout.LayoutParams(0, dp(44), 1f))
        controlBar.addView(fullscreenButton, LinearLayout.LayoutParams(dp(108), dp(44)))
        addView(controlBar, LayoutParams(LayoutParams.MATCH_PARENT, dp(48), Gravity.BOTTOM))
        playPauseButton.setOnClickListener { if (isPlaying()) pause() else start() }
        fullscreenButton.setOnClickListener { setFullscreenState(!isFullscreen, notify = true) }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar) { userSeeking = true }
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                userSeeking = false
                seekTo(seekBar.progress)
            }
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) = Unit
        })
    }

    fun setOnFullscreenToggleListener(listener: (Boolean) -> Unit) { fullscreenListener = listener }

    fun exitFullscreen(): Boolean {
        if (!isFullscreen) return false
        setFullscreenState(false, notify = true)
        return true
    }

    fun setVideoPath(path: String) {
        videoPath = path
        if (textureView.isAvailable) preparePlayer(textureView.surfaceTexture!!)
    }

    fun setRotationDegrees(degrees: Int) {
        rotationDegrees = ((degrees % 360) + 360) % 360
        textureView.rotation = rotationDegrees.toFloat()
        requestLayout()
    }

    fun stopPlayback() {
        handler.removeCallbacks(progressUpdater)
        mediaPlayer?.release()
        mediaPlayer = null
        isPrepared = false
    }

    override fun onDetachedFromWindow() {
        stopPlayback()
        super.onDetachedFromWindow()
    }

    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        preparePlayer(surfaceTexture)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        stopPlayback()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    private fun preparePlayer(surfaceTexture: SurfaceTexture) {
        val path = videoPath ?: return
        stopPlayback()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(path)
            val surface = Surface(surfaceTexture)
            setSurface(surface)
            surface.release()
            setOnPreparedListener {
                isPrepared = true
                this@RotatableVideoPlayer.videoWidth = it.videoWidth
                this@RotatableVideoPlayer.videoHeight = it.videoHeight
                requestLayout()
                handler.removeCallbacks(progressUpdater)
                handler.post(progressUpdater)
                if (startWhenPrepared) start()
            }
            setOnVideoSizeChangedListener { _, width, height ->
                this@RotatableVideoPlayer.videoWidth = width
                this@RotatableVideoPlayer.videoHeight = height
                requestLayout()
            }
            prepareAsync()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val (childWidth, childHeight) = fittedTextureSize(measuredWidth, measuredHeight)
        textureView.measure(
            MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(childHeight, MeasureSpec.EXACTLY)
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val width = right - left
        val height = bottom - top
        val (childWidth, childHeight) = fittedTextureSize(width, height)
        val childLeft = (width - childWidth) / 2
        val childTop = (height - childHeight) / 2
        textureView.layout(childLeft, childTop, childLeft + childWidth, childTop + childHeight)
        textureView.pivotX = childWidth / 2f
        textureView.pivotY = childHeight / 2f
    }

    private fun fittedTextureSize(containerWidth: Int, containerHeight: Int): Pair<Int, Int> {
        if (containerWidth <= 0 || containerHeight <= 0 || videoWidth <= 0 || videoHeight <= 0) {
            return containerWidth.coerceAtLeast(1) to containerHeight.coerceAtLeast(1)
        }
        val quarterTurn = rotationDegrees == 90 || rotationDegrees == 270
        val displayVideoWidth = if (quarterTurn) videoHeight else videoWidth
        val displayVideoHeight = if (quarterTurn) videoWidth else videoHeight
        val scale = minOf(
            containerWidth.toFloat() / displayVideoWidth,
            containerHeight.toFloat() / displayVideoHeight
        )
        val displayWidth = (displayVideoWidth * scale).toInt().coerceAtLeast(1)
        val displayHeight = (displayVideoHeight * scale).toInt().coerceAtLeast(1)
        return if (quarterTurn) displayHeight to displayWidth else displayWidth to displayHeight
    }

    fun start() {
        startWhenPrepared = true
        if (isPrepared) mediaPlayer?.takeIf { !it.isPlaying }?.start()
        updateControls()
    }

    fun pause() {
        startWhenPrepared = false
        if (isPrepared) mediaPlayer?.takeIf { it.isPlaying }?.pause()
        updateControls()
    }

    private fun getDuration(): Int = if (isPrepared) mediaPlayer?.duration ?: 0 else 0
    private fun getCurrentPosition(): Int = if (isPrepared) mediaPlayer?.currentPosition ?: 0 else 0
    private fun seekTo(position: Int) { if (isPrepared) mediaPlayer?.seekTo(position) }
    private fun isPlaying(): Boolean = isPrepared && mediaPlayer?.isPlaying == true

    private fun updateControls() {
        val duration = getDuration().coerceAtLeast(0)
        val position = getCurrentPosition().coerceIn(0, duration.coerceAtLeast(0))
        playPauseButton.text = if (isPlaying()) "Pause" else "Play"
        if (!userSeeking) {
            seekBar.max = duration.coerceAtLeast(1)
            seekBar.progress = position
        }
        timeView.text = "${formatTime(position)} / ${formatTime(duration)}"
    }

    private fun setFullscreenState(fullscreen: Boolean, notify: Boolean) {
        isFullscreen = fullscreen
        fullscreenButton.text = if (fullscreen) "Exit" else "Fullscreen"
        if (notify) fullscreenListener?.invoke(fullscreen)
    }

    private fun formatTime(milliseconds: Int): String {
        val totalSeconds = milliseconds / 1000
        return "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
