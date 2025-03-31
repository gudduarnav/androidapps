package com.example.musicplayer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import java.text.SimpleDateFormat
import java.util.*

data class Song(val uri: Uri, val name: String)

class MainActivity : AppCompatActivity() {

    private lateinit var mediaPlayer: MediaPlayer
    private var songs: MutableList<Song> = mutableListOf()
    private var currentSongIndex = 0

    // UI elements for folder and song info.
    private lateinit var folderNameTextView: TextView
    private lateinit var songNameTextView: TextView

    // UI elements for date/time
    private lateinit var dateTextView: TextView
    private lateinit var timeTextView: TextView

    // Header buttons (exit, app switch, minimize)
    private lateinit var exitButton: Button
    private lateinit var appSwitchButton: Button
    private lateinit var minimizeButton: Button

    private val PICK_FOLDER_REQUEST_CODE = 1
    private val PREFS_NAME = "MusicPlayerPrefs"
    private val FOLDER_URI_KEY = "folder_uri"

    // Handler to update date and time each second.
    private val handler = Handler(Looper.getMainLooper())
    private val dateTimeRunnable = object : Runnable {
        override fun run() {
            updateDateTime()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Title is defined in the layout as a TextView at the top.

        // Initialize UI elements.
        folderNameTextView = findViewById(R.id.folderNameTextView)
        songNameTextView = findViewById(R.id.songNameTextView)
        dateTextView = findViewById(R.id.dateTextView)
        timeTextView = findViewById(R.id.timeTextView)
        exitButton = findViewById(R.id.exitButton)
        appSwitchButton = findViewById(R.id.appSwitchButton)
        minimizeButton = findViewById(R.id.minimizeButton)

        // Folder selection and music controls.
        findViewById<Button>(R.id.pickFolderButton).setOnClickListener { pickFolder() }
        findViewById<Button>(R.id.playButton).setOnClickListener { playSong() }
        findViewById<Button>(R.id.pauseButton).setOnClickListener { pauseSong() }
        findViewById<Button>(R.id.stopButton).setOnClickListener { stopSong() }
        findViewById<Button>(R.id.nextButton).setOnClickListener { nextSong() }
        findViewById<Button>(R.id.previousButton).setOnClickListener { previousSong() }

        // Header buttons.
        exitButton.setOnClickListener {
            finishAffinity()
            System.exit(0)
        }
        appSwitchButton.setOnClickListener {
            startActivity(Intent(this, SwitchActivity::class.java))
        }
        minimizeButton.setOnClickListener {
            moveTaskToBack(true)
        }

        // Check for a saved folder URI.
        val sharedPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val folderUriString = sharedPrefs.getString(FOLDER_URI_KEY, null)
        if (folderUriString != null) {
            val folderUri = Uri.parse(folderUriString)
            val folderFile = DocumentFile.fromTreeUri(this, folderUri)
            folderNameTextView.text = "Folder: ${folderFile?.name ?: "Unknown"}"
            loadSongsFromFolder(folderUri)
        } else {
            folderNameTextView.text = "No folder selected"
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(dateTimeRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(dateTimeRunnable)
    }

    private fun updateDateTime() {
        val now = Calendar.getInstance().time
        // Format: e.g. "Monday, August 7, 2023"
        val dateFormat = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault())
        // Format time with seconds e.g. "02:15:45 PM"
        val timeFormat = SimpleDateFormat("hh:mm:ss a", Locale.getDefault())
        dateTextView.text = dateFormat.format(now)
        timeTextView.text = timeFormat.format(now)
    }

    private fun pickFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        startActivityForResult(intent, PICK_FOLDER_REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_FOLDER_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                // Persist permission.
                val takeFlags: Int = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION)
                contentResolver.takePersistableUriPermission(uri, takeFlags)

                // Save folder URI.
                val sharedPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                sharedPrefs.edit().putString(FOLDER_URI_KEY, uri.toString()).apply()

                // Update folder name.
                val folderFile = DocumentFile.fromTreeUri(this, uri)
                folderNameTextView.text = "Folder: ${folderFile?.name ?: "Unknown"}"

                loadSongsFromFolder(uri)
            }
        }
    }

    private fun loadSongsFromFolder(treeUri: Uri) {
        songs.clear()
        val rootDocumentFile = DocumentFile.fromTreeUri(this, treeUri)
        rootDocumentFile?.let { scanFolderRecursively(it) }
        if (songs.isNotEmpty()) {
            currentSongIndex = 0
            prepareMediaPlayer(songs[currentSongIndex])
        } else {
            songNameTextView.text = "No songs found"
        }
    }

    private fun scanFolderRecursively(folder: DocumentFile) {
        folder.listFiles().forEach { file ->
            if (file.isDirectory) {
                scanFolderRecursively(file)
            } else if (file.isFile && isAudioFile(file.name ?: "")) {
                songs.add(Song(file.uri, file.name ?: "Unknown"))
            }
        }
    }

    private fun isAudioFile(fileName: String): Boolean {
        val audioExtensions = listOf("mp3", "wav", "m4a", "ogg")
        return audioExtensions.any { fileName.endsWith(it, ignoreCase = true) }
    }

    private fun updateSongText(state: String) {
        if (songs.isNotEmpty()) {
            songNameTextView.text =
                "$state: ${songs[currentSongIndex].name} (Song #${currentSongIndex + 1} of ${songs.size})"
        }
    }

    private fun prepareMediaPlayer(song: Song) {
        if (::mediaPlayer.isInitialized) {
            mediaPlayer.reset()
            mediaPlayer.release()
        }
        updateSongText("Playing")
        mediaPlayer = MediaPlayer().apply {
            setDataSource(this@MainActivity, song.uri)
            setOnCompletionListener { nextSong() }
            prepare()
        }
    }

    private fun playSong() {
        if (songs.isEmpty()) return
        if (!::mediaPlayer.isInitialized) {
            prepareMediaPlayer(songs[currentSongIndex])
        }
        mediaPlayer.start()
        updateSongText("Playing")
    }

    private fun pauseSong() {
        if (::mediaPlayer.isInitialized && mediaPlayer.isPlaying) {
            mediaPlayer.pause()
            updateSongText("Paused")
        }
    }

    private fun stopSong() {
        if (::mediaPlayer.isInitialized) {
            if (mediaPlayer.isPlaying) {
                mediaPlayer.pause()
            }
            mediaPlayer.seekTo(0)
            updateSongText("Stopped")
        }
    }

    private fun nextSong() {
        if (songs.isNotEmpty()) {
            currentSongIndex = (currentSongIndex + 1) % songs.size
            prepareMediaPlayer(songs[currentSongIndex])
            mediaPlayer.start()
        }
    }

    private fun previousSong() {
        if (songs.isNotEmpty()) {
            currentSongIndex =
                if (currentSongIndex - 1 < 0) songs.size - 1 else currentSongIndex - 1
            prepareMediaPlayer(songs[currentSongIndex])
            mediaPlayer.start()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::mediaPlayer.isInitialized) {
            mediaPlayer.release()
        }
    }
}
