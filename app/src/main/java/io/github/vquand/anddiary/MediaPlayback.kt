package io.github.vquand.anddiary

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import java.io.File

internal class MediaPlayback(
    private val context: Context,
    private val onAudioStateChanged: () -> Unit,
) {
    private var videoDialog: Dialog? = null
    private var audioPlayer: MediaPlayer? = null
    var playingAudioId: String? = null
        private set

    fun playVideo(label: String, file: File) {
        stopAudio()
        if (!file.exists()) {
            Toast.makeText(context, R.string.media_play_failed, Toast.LENGTH_SHORT).show()
            return
        }
        videoDialog?.dismiss()
        val container = FrameLayout(context).apply {
            setBackgroundColor(0xff141311.toInt())
        }
        val videoView = VideoView(context)
        val controller = MediaController(context)
        controller.setAnchorView(videoView)
        videoView.setMediaController(controller)
        videoView.setVideoPath(file.absolutePath)
        videoView.setOnErrorListener { _, _, _ ->
            Toast.makeText(context, R.string.media_play_failed, Toast.LENGTH_SHORT).show()
            videoDialog?.dismiss()
            true
        }
        container.addView(
            videoView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )
        videoDialog = AlertDialog.Builder(context)
            .setTitle(label)
            .setView(container)
            .setPositiveButton(android.R.string.ok, null)
            .create().also { dialog ->
                dialog.setOnDismissListener {
                    videoView.stopPlayback()
                    videoDialog = null
                }
                dialog.show()
                videoView.start()
            }
    }

    fun toggleAudio(itemId: String, file: File) {
        if (playingAudioId == itemId) {
            stopAudio()
            return
        }
        stopAudio()
        if (!file.exists()) {
            Toast.makeText(context, R.string.media_play_failed, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val player = MediaPlayer()
            audioPlayer = player
            player.apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener { stopAudio() }
                setOnErrorListener { _, _, _ ->
                    stopAudio()
                    Toast.makeText(context, R.string.media_play_failed, Toast.LENGTH_SHORT).show()
                    true
                }
                prepare()
                start()
            }
            playingAudioId = itemId
            onAudioStateChanged()
        } catch (error: Exception) {
            Log.e("DiaryMedia", "Could not play audio", error)
            stopAudio()
            Toast.makeText(context, R.string.media_play_failed, Toast.LENGTH_SHORT).show()
        }
    }

    fun stopAudio() {
        audioPlayer?.let { player ->
            player.runCatching { if (isPlaying) stop() }
            player.runCatching { release() }
        }
        audioPlayer = null
        if (playingAudioId != null) {
            playingAudioId = null
            onAudioStateChanged()
        }
    }

    fun release() {
        stopAudio()
        videoDialog?.dismiss()
        videoDialog = null
    }
}
