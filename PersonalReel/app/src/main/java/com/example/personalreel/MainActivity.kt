package com.example.personalreel

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ui.PlayerView
import kotlin.math.abs
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var exoPlayer: ExoPlayer
    private lateinit var tvBottomInfo: TextView

    // List of video URIs loaded from the selected folder.
    private val videoUris: MutableList<Uri> = mutableListOf()
    // Random playback order for the current cycle (list of indices into videoUris).
    private var randomOrder: MutableList<Int> = mutableListOf()
    // Pointer into the current random order cycle.
    private var currentIndex = 0

    companion object {
        private const val REQUEST_FOLDER = 1001
        private const val SWIPE_THRESHOLD = 100f  // in pixels
        private const val PREFS_NAME = "app_prefs"
        private const val KEY_FOLDER_URI = "folderUri"
        private const val KEY_LAST_VIDEO_URI = "lastVideoUri"
    }

    // For swipe detection.
    private var startX = 0f
    private var startY = 0f

    private var appStartTime: Long = 0L

    // Handler to update the bottom overlay every second.
    private val handler = Handler(Looper.getMainLooper())
    private val updateInfoRunnable = object : Runnable {
        override fun run() {
            if (videoUris.isNotEmpty() && randomOrder.isNotEmpty())
                updateBottomInfo(videoUris[randomOrder[currentIndex]])
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Set default orientation to portrait (will adjust per video).
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        setContentView(R.layout.activity_main)
        hideSystemUI()
        appStartTime = System.currentTimeMillis()

        playerView = findViewById(R.id.playerView)
        tvBottomInfo = findViewById(R.id.tvBottomInfo)

        // Initialize ExoPlayer.
        exoPlayer = ExoPlayer.Builder(this).build()
        playerView.player = exoPlayer
        exoPlayer.repeatMode = Player.REPEAT_MODE_OFF  // We'll handle cycle looping manually.

        // Listen for video completion and errors.
        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    nextVideo()
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                // Skip unplayable video.
                val failedUri = videoUris[randomOrder[currentIndex]]
                videoUris.remove(failedUri)
                generateRandomOrder()
                nextVideo()
            }
        })

        // Modified swipe detection: record both X and Y.
        playerView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x
                    startY = event.y
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val deltaX = event.x - startX
                    val deltaY = event.y - startY
                    if (abs(deltaX) < SWIPE_THRESHOLD && abs(deltaY) < SWIPE_THRESHOLD) {
                        true
                    } else {
                        // Determine dominant direction.
                        if (abs(deltaX) > abs(deltaY)) {
                            // Horizontal swipe.
                            if (deltaX < 0) nextVideo() else previousVideo()
                        } else {
                            // Vertical swipe.
                            if (deltaY < 0) nextVideo() else previousVideo()
                        }
                        true
                    }
                }
                else -> false
            }
        }

        // Check for saved folder URI.
        val sharedPref = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val folderUriString = sharedPref.getString(KEY_FOLDER_URI, null)
        if (folderUriString == null) {
            // No folder saved: prompt user.
            pickFolder()
        } else {
            val folderUri = Uri.parse(folderUriString)
            videoUris.clear()
            scanFolderForVideos(folderUri)
            if (videoUris.isNotEmpty()) {
                generateRandomOrder()
                // Attempt to restore last played video.
                val lastVideoUriString = sharedPref.getString(KEY_LAST_VIDEO_URI, null)
                currentIndex = 0
                if (lastVideoUriString != null) {
                    val lastUri = Uri.parse(lastVideoUriString)
                    val idx = videoUris.indexOf(lastUri)
                    if (idx != -1) {
                        val pos = randomOrder.indexOf(idx)
                        if (pos != -1) currentIndex = pos
                    }
                }
                playVideoAtCurrentCycleIndex()
            } else {
                pickFolder()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(updateInfoRunnable)
        exoPlayer.playWhenReady = true
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(updateInfoRunnable)
        exoPlayer.playWhenReady = false
        if (videoUris.isNotEmpty() && randomOrder.isNotEmpty()) {
            val currentUri = videoUris[randomOrder[currentIndex]]
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_LAST_VIDEO_URI, currentUri.toString()).apply()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer.release()
    }

    // Hide system UI for a full-screen immersive experience.
    private fun hideSystemUI() {
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                        View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUI()
    }

    // Prompt the user to select a folder.
    private fun pickFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        startActivityForResult(intent, REQUEST_FOLDER)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_FOLDER && resultCode == Activity.RESULT_OK) {
            val folderUri = data?.data ?: return
            contentResolver.takePersistableUriPermission(
                folderUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_FOLDER_URI, folderUri.toString()).apply()
            videoUris.clear()
            scanFolderForVideos(folderUri)
            if (videoUris.isNotEmpty()) {
                generateRandomOrder()
                currentIndex = 0
                playVideoAtCurrentCycleIndex()
            }
        }
    }

    /**
     * Recursively scans the selected folder for video files using the Storage Access Framework.
     */
    private fun scanFolderForVideos(folderUri: Uri) {
        val docId = DocumentsContract.getTreeDocumentId(folderUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(folderUri, docId)
        val cursor = contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            ),
            null, null, null
        )
        cursor?.use {
            while (it.moveToNext()) {
                val documentId = it.getString(0)
                val mimeType = it.getString(1)
                val documentUri = DocumentsContract.buildDocumentUriUsingTree(folderUri, documentId)
                if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                    scanFolderForVideos(documentUri)
                } else if (mimeType.startsWith("video/")) {
                    videoUris.add(documentUri)
                }
            }
        }
    }

    /**
     * Generates a new random order (shuffled indices) for playback.
     */
    private fun generateRandomOrder() {
        randomOrder = videoUris.indices.toMutableList()
        randomOrder.shuffle()
    }

    /**
     * Plays the video corresponding to the current random order index.
     */
    private fun playVideoAtCurrentCycleIndex() {
        if (videoUris.isNotEmpty() && randomOrder.isNotEmpty()) {
            val uri = videoUris[randomOrder[currentIndex]]
            adjustOrientation(uri)
            playVideo(uri)
        }
    }

    /**
     * Uses ExoPlayer to play the provided video URI.
     */
    private fun playVideo(uri: Uri) {
        val mediaItem = MediaItem.fromUri(uri)
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
        updateBottomInfo(uri)
    }

    /**
     * Adjusts screen orientation based on the video's aspect ratio.
     * If width > height then forces landscape; otherwise, portrait.
     */
    private fun adjustOrientation(uri: Uri) {
        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(this, uri)
            val widthStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val heightStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            retriever.release()
            if (widthStr != null && heightStr != null) {
                val width = widthStr.toIntOrNull() ?: 0
                val height = heightStr.toIntOrNull() ?: 0
                requestedOrientation = if (width > height) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        } catch (e: Exception) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    /**
     * Updates the bottom overlay with multiple lines:
     * 1. Video index: "[#x of y]"
     * 2. Video name (without file extension)
     * 3. Percentage of video left (e.g., "Left: 37%")
     * 4. App active time (HH:MM)
     * 5. Current date and time ("EEE, MMM d, yyyy hh:mm a")
     * 6. "Personal Reel App by"
     * 7. "Arnav Mukhopadhyay"
     * 8. "EMAIL: gudduarnav@gmail.com"
     * The text color is set to white.
     */
    private fun updateBottomInfo(currentVideoUri: Uri) {
        val line1 = "[#${currentIndex + 1} of ${randomOrder.size}]"
        val documentFile = DocumentFile.fromSingleUri(this, currentVideoUri)
        val fullName = documentFile?.name ?: currentVideoUri.lastPathSegment ?: "Unknown"
        val videoName = fullName.substringBeforeLast(".", fullName)
        val line2 = videoName
        val duration = exoPlayer.duration
        val position = exoPlayer.currentPosition
        val percentLeft = if (duration > 0) ((duration - position) * 100 / duration).toInt() else 0
        val line3 = "Left: $percentLeft%"
        val activeMinutes = ((System.currentTimeMillis() - appStartTime) / 60000).toInt()
        val hours = activeMinutes / 60
        val minutes = activeMinutes % 60
        val line4 = String.format("%02d:%02d", hours, minutes)
        val sdf = SimpleDateFormat("EEE, MMM d, yyyy hh:mm a", Locale.getDefault())
        val line5 = sdf.format(Date())
        val line6 = "Personal Reel App by"
        val line7 = "Arnav Mukhopadhyay"
        val line8 = "EMAIL: gudduarnav@gmail.com"
        val infoText = listOf(line1, line2, line3, line4, line5, line6, line7, line8).joinToString("\n")
        tvBottomInfo.text = infoText
        tvBottomInfo.setTextColor(android.graphics.Color.WHITE)
    }

    /**
     * Advances to the next video in the random cycle. If the cycle is complete, reshuffles.
     */
    private fun nextVideo() {
        if (randomOrder.isNotEmpty()) {
            currentIndex = if (currentIndex < randomOrder.size - 1) currentIndex + 1 else {
                generateRandomOrder()
                0
            }
            playVideoAtCurrentCycleIndex()
        }
    }

    /**
     * Goes to the previous video in the current cycle.
     */
    private fun previousVideo() {
        if (randomOrder.isNotEmpty()) {
            currentIndex = if (currentIndex > 0) currentIndex - 1 else randomOrder.size - 1
            playVideoAtCurrentCycleIndex()
        }
    }
}


