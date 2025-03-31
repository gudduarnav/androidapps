package com.example.personalreel

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
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
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ui.PlayerView
import kotlin.math.abs
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var exoPlayer: ExoPlayer
    private lateinit var tvVideoName: TextView
    private lateinit var tvVideoNumber: TextView
    private lateinit var tvDateTime: TextView

    // List to hold discovered video URIs from the selected folder.
    private val videoUris: MutableList<Uri> = mutableListOf()
    // List of indices representing the random playback order for the cycle.
    private var randomOrder: MutableList<Int> = mutableListOf()
    // Pointer into the current random order cycle.
    private var currentIndex = 0

    companion object {
        private const val REQUEST_FOLDER = 1001
        private const val SWIPE_THRESHOLD = 100f // in pixels
        private const val PREFS_NAME = "app_prefs"
        private const val KEY_FOLDER_URI = "folderUri"
    }

    private var startY = 0f

    // Handler to update the date/time overlay every second.
    private val handler = Handler(Looper.getMainLooper())
    private val updateTimeRunnable = object : Runnable {
        override fun run() {
            val sdf = SimpleDateFormat("EEEE, MMM d, yyyy HH:mm:ss", Locale.getDefault())
            tvDateTime.text = sdf.format(Date())
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Force portrait orientation.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        setContentView(R.layout.activity_main)
        hideSystemUI()

        playerView = findViewById(R.id.playerView)
        tvVideoName = findViewById(R.id.tvVideoName)
        tvVideoNumber = findViewById(R.id.tvVideoNumber)
        tvDateTime = findViewById(R.id.tvDateTime)

        // Initialize ExoPlayer and attach it to the PlayerView.
        exoPlayer = ExoPlayer.Builder(this).build()
        playerView.player = exoPlayer
        // We set repeat mode off since we want to auto-advance manually.
        exoPlayer.repeatMode = Player.REPEAT_MODE_ALL //Player.REPEAT_MODE_OFF

        // Add a listener to detect when a video finishes, then auto advance.
        /*exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    nextVideo()
                }
            }
        })*/

        // Set up manual swipe detection on the PlayerView for vertical swipes.
        playerView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.y
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val deltaY = event.y - startY
                    if (abs(deltaY) > SWIPE_THRESHOLD) {
                        if (deltaY < 0) {
                            nextVideo()
                        } else {
                            previousVideo()
                        }
                    }
                    true
                }
                else -> false
            }
        }

        // Check SharedPreferences for a saved folder URI.
        val sharedPref = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val folderUriString = sharedPref.getString(KEY_FOLDER_URI, null)
        if (folderUriString == null) {
            // No folder saved: prompt the user.
            pickFolder()
        } else {
            val folderUri = Uri.parse(folderUriString)
            videoUris.clear()
            scanFolderForVideos(folderUri)
            if (videoUris.isNotEmpty()) {
                generateRandomOrder()
                currentIndex = 0
                playVideoAtCurrentCycleIndex()
            } else {
                // Folder exists but no videos found; ask user to select one.
                pickFolder()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(updateTimeRunnable)
        exoPlayer.playWhenReady = true
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(updateTimeRunnable)
        exoPlayer.playWhenReady = false
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

    // Prompt the user to select a folder using ACTION_OPEN_DOCUMENT_TREE.
    private fun pickFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        startActivityForResult(intent, REQUEST_FOLDER)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_FOLDER && resultCode == Activity.RESULT_OK) {
            val folderUri = data?.data ?: return
            // Persist permissions.
            contentResolver.takePersistableUriPermission(
                folderUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            // Save folder URI in SharedPreferences.
            val sharedPref = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            with(sharedPref.edit()) {
                putString(KEY_FOLDER_URI, folderUri.toString())
                apply()
            }
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
     * Generates a new random order (shuffled indices) for the video playback cycle.
     */
    private fun generateRandomOrder() {
        randomOrder = videoUris.indices.toMutableList()
        randomOrder.shuffle()
    }

    /**
     * Plays the video corresponding to the current index in the random cycle.
     */
    private fun playVideoAtCurrentCycleIndex() {
        if (videoUris.isNotEmpty() && randomOrder.isNotEmpty()) {
            val uri = videoUris[randomOrder[currentIndex]]
            playVideo(uri)
        }
    }

    /**
     * Plays the given video URI using ExoPlayer.
     */
    private fun playVideo(uri: Uri) {
        val mediaItem = MediaItem.fromUri(uri)
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true

        updateVideoNumberText()
        updateVideoName(uri)
    }

    /**
     * Updates the overlay showing the current video's position in the cycle.
     */
    private fun updateVideoNumberText() {
        tvVideoNumber.text = "#${currentIndex + 1} of ${randomOrder.size}"
    }

    /**
     * Updates the overlay with the name of the currently playing video.
     */
    private fun updateVideoName(uri: Uri) {
        val documentFile = DocumentFile.fromSingleUri(this, uri)
        val videoName = documentFile?.name ?: uri.lastPathSegment ?: "Unknown"
        tvVideoName.text = videoName
    }

    /**
     * Advances to the next video in the cycle. If the cycle is finished, reshuffle and start a new cycle.
     */
    private fun nextVideo() {
        if (randomOrder.isNotEmpty()) {
            if (currentIndex < randomOrder.size - 1) {
                currentIndex++
            } else {
                generateRandomOrder()
                currentIndex = 0
            }
            playVideoAtCurrentCycleIndex()
        }
    }

    /**
     * Goes to the previous video in the current cycle.
     */
    private fun previousVideo() {
        if (randomOrder.isNotEmpty()) {
            if (currentIndex > 0) {
                currentIndex--
            } else {
                currentIndex = randomOrder.size - 1
            }
            playVideoAtCurrentCycleIndex()
        }
    }
}
