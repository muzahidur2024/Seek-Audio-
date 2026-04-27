package com.seekaudio.data.repository

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.seekaudio.data.model.Song
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val songDao: SongDao,
    private val playlistDao: PlaylistDao,
) {
    // ── MediaStore scanner ────────────────────────────────────────────────────

    suspend fun scanDevice(): Int = withContext(Dispatchers.IO) {
        val songs = mutableListOf<Song>()
        val existingSongs = songDao.getAllSongsSnapshot()
        val existingById = existingSongs.associateBy { it.id }
        val existingByUri = existingSongs.associateBy { it.uri }
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection  = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.RELATIVE_PATH,
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} > 30000"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        try {
            context.contentResolver.query(collection, projection, selection, null, sortOrder)?.use { cursor ->
                val idCol      = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol   = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol  = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol   = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val durCol     = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val yearCol    = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
                val trackCol   = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
                val sizeCol    = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val dateCol    = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val relPathCol = cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)

                while (cursor.moveToNext()) {
                    val id  = cursor.getLong(idCol)
                    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                    val path = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && relPathCol != -1) {
                        cursor.getString(relPathCol) ?: ""
                    } else {
                        ""
                    }
                    val existing = existingById[id] ?: existingByUri[uri.toString()]
                    songs.add(Song(
                        id          = id,
                        title       = cursor.getString(titleCol)  ?: "Unknown Title",
                        artist      = cursor.getString(artistCol) ?: "Unknown Artist",
                        album       = cursor.getString(albumCol)  ?: "Unknown Album",
                        albumId     = cursor.getLong(albumIdCol),
                        duration    = cursor.getLong(durCol),
                        path        = path,
                        uri         = uri.toString(),
                        genre       = "",
                        year        = cursor.getInt(yearCol),
                        trackNumber = cursor.getInt(trackCol),
                        size        = cursor.getLong(sizeCol),
                        dateAdded   = cursor.getLong(dateCol),
                        isLiked     = existing?.isLiked ?: false,
                        playCount   = existing?.playCount ?: 0,
                        lastPlayed  = existing?.lastPlayed ?: 0L,
                    ))
                }
            }
        } catch (_: Exception) {
            return@withContext 0
        }
        songDao.insertAll(songs)
        songs.size
    }

    // ── Album art ─────────────────────────────────────────────────────────────

    fun getAlbumArtUri(albumId: Long): Uri =
        ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId)

    // ── Song flows ────────────────────────────────────────────────────────────

    fun getAllSongs()               = songDao.getAllSongs()
    fun getLikedSongs()             = songDao.getLikedSongs()
    fun searchSongs(q: String)      = songDao.searchSongs(q)
    fun getSongsByArtist(a: String) = songDao.getSongsByArtist(a)
    fun getSongsByAlbum(a: String)  = songDao.getSongsByAlbum(a)
    fun getMostPlayed()             = songDao.getMostPlayed()
    fun getRecentlyPlayed()         = songDao.getRecentlyPlayed()
    fun getAllArtists()              = songDao.getAllArtists()
    fun getAllAlbums()               = songDao.getAllAlbums()
    fun getAllGenres()               = songDao.getAllGenres()
    suspend fun getSongById(id: Long)           = songDao.getSongById(id)
    suspend fun getSongByUri(uri: String)       = songDao.getSongByUri(uri)
    suspend fun getAllSongsSnapshot(): List<Song> = songDao.getAllSongsSnapshot()

    suspend fun setLiked(id: Long, liked: Boolean) = songDao.setLiked(id, liked)
    suspend fun incrementPlayCount(id: Long)        = songDao.incrementPlayCount(id)
    suspend fun deleteFromLibrary(id: Long)         = songDao.deleteById(id)
    suspend fun updateTags(id: Long, title: String, artist: String, album: String, genre: String, year: Int) =
        songDao.updateTags(id, title, artist, album, genre, year)
    suspend fun deleteFromDeviceAndLibrary(song: Song): Boolean = withContext(Dispatchers.IO) {
        val deleted = try {
            context.contentResolver.delete(song.contentUri, null, null) > 0
        } catch (_: Exception) {
            false
        }
        if (deleted) {
            songDao.deleteById(song.id)
        }
        deleted
    }

    // ── Playlist flows ────────────────────────────────────────────────────────

    fun getAllPlaylists()                                    = playlistDao.getAllPlaylists()
    suspend fun createPlaylist(name: String)                = playlistDao.insert(com.seekaudio.data.model.Playlist(name = name))
    suspend fun deletePlaylist(playlist: com.seekaudio.data.model.Playlist) = playlistDao.delete(playlist)
    suspend fun renamePlaylist(id: Long, name: String)      = playlistDao.rename(id, name)
    suspend fun addToPlaylist(playlistId: Long, songId: Long, pos: Int = 0) =
        playlistDao.addSongToPlaylist(com.seekaudio.data.model.PlaylistSongCrossRef(playlistId, songId, pos))
    suspend fun removeFromPlaylist(playlistId: Long, songId: Long) =
        playlistDao.removeSongFromPlaylist(playlistId, songId)
}
