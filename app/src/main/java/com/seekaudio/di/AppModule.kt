package com.seekaudio.di

import android.content.Context
import androidx.room.Room
import com.seekaudio.data.repository.SeekAudioDatabase
import com.seekaudio.data.repository.SongDao
import com.seekaudio.data.repository.PlaylistDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): SeekAudioDatabase =
        Room.databaseBuilder(ctx, SeekAudioDatabase::class.java, "seek_audio_db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideSongDao(db: SeekAudioDatabase): SongDao = db.songDao()

    @Provides
    fun providePlaylistDao(db: SeekAudioDatabase): PlaylistDao = db.playlistDao()
}
