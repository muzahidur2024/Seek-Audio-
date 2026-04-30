# Media3 / ExoPlayer
-keep class androidx.media3.** { *; }
-keep class com.google.android.exoplayer2.** { *; }

# Hilt
-keep class dagger.hilt.** { *; }
-keep @dagger.hilt.android.AndroidEntryPoint class * { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao class *

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Glide
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule { <init>(...); }
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** { *; }

# Model classes
-keep class com.seekaudio.data.model.** { *; }

# Playback/session classes can be sensitive to shrinking in release.
-keep class com.seekaudio.service.PlaybackService { *; }
-keep class com.seekaudio.service.PlaybackService$* { *; }
-keep class com.seekaudio.ui.player.PlayerViewModel { *; }
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
