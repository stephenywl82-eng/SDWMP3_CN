# ============================================
# SDWMP2 ProGuard Rules — Hardened
# ============================================

# === General ===
-keepattributes *Annotation*
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations,RuntimeInvisibleParameterAnnotations
-keepattributes SourceFile,LineNumberTable
-keepattributes Exceptions

-renamesourcefileattribute SourceFile
-repackageclasses 'com.sdw.music'
-allowaccessmodification
-mergeinterfacesaggressively
-overloadaggressively
-useuniqueclassmembernames
-dontnote
-dontwarn javax.annotation.**
-dontwarn kotlin.Unit
-keep class kotlin.Metadata { *; }

# === Data model — keep for Gson serialization ===
-keep class com.sdw.music.player.model.Song { *; }
-keep class com.sdw.music.player.core.model.** { *; }
-keep class com.sdw.music.player.core.audio.EqualizerManager$EqPreset { *; }
-keep class com.sdw.music.player.core.audio.EqualizerManager$EqBand { *; }
-keep class com.sdw.music.player.core.audio.AutoEqPresetManager$* { *; }
-keep class com.sdw.music.player.core.lyrics.LyricResult { *; }

# === Gson — minimal keep ===
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers class * {
    @com.google.gson.annotations.Expose <fields>;
}
-keepclassmembers enum * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# === Hilt / Dagger ===
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep,allowobfuscation,allowshrinking class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper
-keep class *_HiltModules { *; }
-keep class *_HiltComponents { *; }
-keep class *_HiltComponents_* { *; }
-keep class *_Factory { *; }
-keep class *_MembersInjector { *; }
-dontwarn dagger.hilt.**

# === JNI / Oboe ===
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.sdw.music.player.core.audio.OboeDirectPlayer {
    native <methods>;
    public <methods>;
}

# === Manifest components ===
-keep class com.sdw.music.player.MainActivity { *; }
-keep class com.sdw.music.player.MusicService { *; }
-keep class com.sdw.music.player.core.audio.MusicService { *; }
-keep class com.sdw.music.player.core.widget.** { *; }
-keep class com.sdw.music.player.widget.** { *; }
-keep class com.sdw.music.player.core.audio.MediaButtonReceiver { *; }

# === Compose runtime ===
-keep class androidx.compose.runtime.** { *; }
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}
-dontwarn androidx.compose.**

# === Material3 dynamic color — keep entire chain so R8 doesn't dead-code-eliminate ===
-keep class com.sdw.music.player.ui.theme.SDWMusicThemeKt {
    *** SDWMusicTheme(...);
}
-keep class com.sdw.music.player.ui.theme.SDWDarkColorSchemeKt {
    *** SDWDarkColorScheme(...);
}
-keep class androidx.compose.material3.ColorScheme {
    *;
}
-keepclassmembers class androidx.compose.material3.ColorSchemeKt {
    public static *** dynamicDarkColorScheme(...);
    public static *** dynamicLightColorScheme(...);
}

# === Coil (image loading) ===
-keep class coil.** { *; }
-keep class coil.compose.** { *; }
-dontwarn coil.**

# === Coroutines ===
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# === Media3 ExoPlayer ===
-keep class androidx.media3.session.MediaSessionService { *; }
-dontwarn androidx.media3.**

# === Enums ===
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# === Serializable / Parcelable ===
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# === ViewBinding ===
-keep class * implements androidx.viewbinding.ViewBinding { *; }

# === App Widget (RemoteViews) ===
-keep class com.sdw.music.player.widget.MusicWidgetProvider { *; }
-keep class com.sdw.music.player.widget.MusicWidgetProvider3x2 { *; }

# === Startup / Application ===
-keep class com.sdw.music.player.SDWApp { *; }

# === Remove logging (keep w for runtime traces) ===
-assumenosideeffects class android.util.Log {
    public static int v(...);
}

# AudioQualityAnalyzer
-keep class com.sdw.audio.analyzer.** { *; }
-keep class com.sdw.music.player.ui.screens.AudioQualityScreen { *; }
-keep class com.sdw.music.player.ui.screens.QualityUiState { *; }
-keep class com.sdw.music.player.ui.screens.SortMode { *; }