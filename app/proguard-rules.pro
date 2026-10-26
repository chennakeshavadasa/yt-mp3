# youtubedl-android (yt-dlp + ffmpeg wrapper) uses reflection/Jackson: keep it whole
-keep class com.yausername.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keep class org.apache.commons.compress.** { *; }
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod
# Optional dependencies of the libraries above that are not bundled
-dontwarn **
