# Keep the app's own classes: they are tiny, and this guarantees the service,
# activity, receiver and overlay classes survive R8 in every AGP version.
-keep class com.floaty.volume.** { *; }

# Readable crash reports (line numbers) at a negligible size cost.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# The app only uses framework APIs; nothing to warn about.
-dontwarn android.**
