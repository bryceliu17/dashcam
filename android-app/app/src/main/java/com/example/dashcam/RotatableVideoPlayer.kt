package com.example.dashcam

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.util.AttributeSet
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import android.widget.MediaController

class RotatableVideoPlayer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs), TextureView.SurfaceTextureListener, MediaController.MediaPlayerControl {
    private val textureView = TextureView(context)
    private val controller = MediaController(context)
    private var mediaPlayer: MediaPlayer? = null
    private var videoPath: String? = null
    private var rotationDegrees = 0
    private var startWhenPrepared = false
    private var isPrepared = false
    private var videoWidth = 0
    private var videoHeight = 0

    init {
        addView(textureView)
        textureView.surfaceTextureListener = this
        controller.setMediaPlayer(this)
        controller.setAnchorView(this)
        setOnClickListener { controller.show() }
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
        controller.hide()
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
                controller.isEnabled = true
                requestLayout()
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

    override fun start() {
        startWhenPrepared = true
        if (isPrepared) mediaPlayer?.takeIf { !it.isPlaying }?.start()
    }

    override fun pause() {
        startWhenPrepared = false
        if (isPrepared) mediaPlayer?.takeIf { it.isPlaying }?.pause()
    }

    override fun getDuration(): Int = if (isPrepared) mediaPlayer?.duration ?: 0 else 0
    override fun getCurrentPosition(): Int = if (isPrepared) mediaPlayer?.currentPosition ?: 0 else 0
    override fun seekTo(position: Int) { if (isPrepared) mediaPlayer?.seekTo(position) }
    override fun isPlaying(): Boolean = isPrepared && mediaPlayer?.isPlaying == true
    override fun getBufferPercentage(): Int = 100
    override fun canPause(): Boolean = true
    override fun canSeekBackward(): Boolean = true
    override fun canSeekForward(): Boolean = true
    override fun getAudioSessionId(): Int = mediaPlayer?.audioSessionId ?: 0
}
