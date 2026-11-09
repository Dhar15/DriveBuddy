package app.drivebuddy.features.music

import android.util.Log
import app.drivebuddy.command.Command
import app.drivebuddy.command.VolumeOp
import app.drivebuddy.features.music.spotify.SpotifyApi
import app.drivebuddy.features.music.spotify.SpotifyAuth
import app.drivebuddy.features.phone.PhoneActions
import app.drivebuddy.service.Conversation
import app.drivebuddy.ui.DriveState

/** "Play …" on Spotify or YouTube Music, and the volume commands. Speaks first, then starts the music. */
class MusicHandler(
    private val c: Conversation,
    private val actions: PhoneActions,
    private val spotifyAuth: SpotifyAuth,
    private val spotify: SpotifyApi,
) {

    private companion object {
        const val TAG = "DriveBuddy"
        const val SPOTIFY = "com.spotify.music"
        const val YOUTUBE_MUSIC = "com.google.android.apps.youtube.music"
        const val RECONNECT_SPOTIFY = "Spotify isn't connected. Open Permissions and tap Connect Spotify."
        const val NEEDS_PERMISSION = "I need one more Spotify permission. Open Permissions and tap Connect Spotify again."
    }

    fun play(command: Command.Play) {
        if (command.target == "youtube music") {
            if (!actions.isInstalled(YOUTUBE_MUSIC)) {
                c.reply("I don't see YouTube Music on this phone.")
                return
            }
            c.reply("Playing ${command.query}.") {
                if (!actions.playFromSearch(command.query, "youtube music")) Log.w(TAG, "could not start playback")
            }
            return
        }
        if (!spotifyAuth.isConnected()) {
            c.reply(RECONNECT_SPOTIFY)
            return
        }
        when (val intent = PlayIntent.parse(command.query)) {
            is PlayIntent.Artist -> { Log.i(TAG, "play: artist request"); playArtist(intent.name); return }
            PlayIntent.Any -> { Log.i(TAG, "play: any song (liked songs, shuffled)"); playLiked(); return }
            is PlayIntent.Song -> Unit
            is PlayIntent.Playlist -> { Log.i(TAG, "play: my playlist request"); playPlaylist(intent.name); return }
            PlayIntent.AnyPlaylist -> { Log.i(TAG, "play: any of my playlists"); playPlaylist(null); return }
        }
        c.publish("Asking Spotify...", DriveState.THINKING)
        // Network calls stay off the main thread. Search first, speak, and only then start the music.
        Thread {
            c.pauseWatchdog() // waking Spotify can take several seconds
            val track = when (val found = spotify.search(command.query)) {
                is SpotifyApi.SearchResult.Found -> found.track
                SpotifyApi.SearchResult.NotFound -> { c.post { c.reply("I couldn't find ${command.query} on Spotify.") }; return@Thread }
                SpotifyApi.SearchResult.NotConnected -> { c.post { c.reply(RECONNECT_SPOTIFY) }; return@Thread }
                is SpotifyApi.SearchResult.Failed -> {
                    Log.w(TAG, "spotify search failed: ${found.why}")
                    c.post { c.reply("I couldn't reach Spotify.") }
                    return@Thread
                }
            }
            c.post { if (!c.destroyed) announceThenPlay(track) }
        }.start()
    }

    fun volume(command: Command.Volume) {
        val change = actions.changeVolume(command.op, command.percent)
        if (change == null) {
            Log.w(TAG, "volume ${command.op} failed")
            c.reply("I couldn't change the volume.")
            return
        }
        Log.i(TAG, "volume ${command.op} ${command.percent}%: ${change.before}% -> ${change.after}%, muted=${change.muted}")
        c.reply(
            when {
                command.op == VolumeOp.MUTE -> "Muted."
                command.op == VolumeOp.UP && change.after == change.before -> "Volume is already at maximum."
                command.op == VolumeOp.DOWN && change.after == change.before -> "Volume is already at minimum."
                else -> "Volume ${change.after} percent."
            }
        )
    }

    /** Speaks first so the voice is heard at full volume, then starts the music. */
    private fun announceThenPlay(track: SpotifyApi.Track) {
        c.reply("Playing ${track.name} by ${track.artist}.") {
            Thread {
                val problem = failureText(spotify.play(track) { actions.openApp(SPOTIFY) })
                if (problem != null) c.post { c.speakLate(problem) }
            }.start()
        }
    }

    /** Speaks first, then starts the shuffled context, like [announceThenPlay] does for a single track. */
    private fun announceThenPlayContext(uri: String, label: String, spoken: String) {
        c.reply("Playing $spoken, shuffled.") {
            Thread {
                val problem = failureText(spotify.playContext(uri, label) { actions.openApp(SPOTIFY) })
                if (problem != null) c.post { c.speakLate(problem) }
            }.start()
        }
    }

    private fun playArtist(name: String) {
        c.publish("Asking Spotify...", DriveState.THINKING)
        Thread {
            c.pauseWatchdog()
            when (val found = spotify.findArtist(name)) {
                is SpotifyApi.ArtistResult.Found ->
                    c.post { if (!c.destroyed) announceThenPlayContext(found.artist.uri, found.artist.name, "songs by ${found.artist.name}") }
                SpotifyApi.ArtistResult.NotFound -> c.post { c.reply("I couldn't find an artist called $name on Spotify.") }
                SpotifyApi.ArtistResult.NotConnected -> c.post { c.reply(RECONNECT_SPOTIFY) }
                is SpotifyApi.ArtistResult.Failed -> {
                    Log.w(TAG, "spotify artist search failed: ${found.why}")
                    c.post { c.reply("I couldn't reach Spotify.") }
                }
            }
        }.start()
    }

    private fun playLiked() {
        c.publish("Asking Spotify...", DriveState.THINKING)
        Thread {
            c.pauseWatchdog()
            when (val liked = spotify.likedSongs()) {
                is SpotifyApi.LikedResult.Ready ->
                    c.post { if (!c.destroyed) announceThenPlayContext(liked.contextUri, "liked songs", "your liked songs") }
                SpotifyApi.LikedResult.Empty -> c.post { c.reply("You don't have any liked songs yet.") }
                SpotifyApi.LikedResult.NeedsPermission -> c.post { c.reply(NEEDS_PERMISSION) }
                SpotifyApi.LikedResult.NotConnected -> c.post { c.reply(RECONNECT_SPOTIFY) }
                is SpotifyApi.LikedResult.Failed -> {
                    Log.w(TAG, "spotify liked songs failed: ${liked.why}")
                    c.post { c.reply("I couldn't reach Spotify.") }
                }
            }
        }.start()
    }

    /** [name] null means "any of my playlists". */
    private fun playPlaylist(name: String?) {
        c.publish("Asking Spotify...", DriveState.THINKING)
        Thread {
            c.pauseWatchdog()
            when (val result = spotify.myPlaylists()) {
                is SpotifyApi.PlaylistsResult.Ready -> {
                    val list = result.playlists
                    val chosen = if (name == null) {
                        list.random()
                    } else {
                        val i = TrackPicker.best(name, list.map { TrackCandidate(it.name, emptyList(), 0) })
                        if (i < 0) null else list[i]
                    }
                    if (chosen == null) {
                        Log.i(TAG, "play: no playlist matched among ${list.size}")
                        c.post { c.reply("I couldn't find a playlist called $name.") }
                    } else {
                        Log.i(TAG, "play: playlist chosen from ${list.size}")
                        c.post { if (!c.destroyed) announceThenPlayContext(chosen.uri, chosen.name, "your playlist ${chosen.name}") }
                    }
                }
                SpotifyApi.PlaylistsResult.Empty -> c.post { c.reply("You don't have any playlists yet.") }
                SpotifyApi.PlaylistsResult.NeedsPermission -> c.post { c.reply(NEEDS_PERMISSION) }
                SpotifyApi.PlaylistsResult.NotConnected -> c.post { c.reply(RECONNECT_SPOTIFY) }
                is SpotifyApi.PlaylistsResult.Failed -> {
                    Log.w(TAG, "spotify playlists failed: ${result.why}")
                    c.post { c.reply("I couldn't reach Spotify.") }
                }
            }
        }.start()
    }

    /** Runs on a background thread. Returns what to say if playback did not start, or null when it did. */
    private fun failureText(played: SpotifyApi.PlayResult): String? =
        when (played) {
            SpotifyApi.PlayResult.Started -> null
            SpotifyApi.PlayResult.NoDevice -> "Spotify isn't running on this phone. Open Spotify once and try again."
            SpotifyApi.PlayResult.NotConnected -> RECONNECT_SPOTIFY
            SpotifyApi.PlayResult.NotPlaying -> "Spotify accepted it but didn't start playing. Please try again."
            SpotifyApi.PlayResult.Refused -> "Spotify refused to play. Check that Premium is active."
            is SpotifyApi.PlayResult.Failed -> {
                Log.w(TAG, "spotify play failed: ${played.why}")
                "Spotify didn't respond."
            }
        }
}