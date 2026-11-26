# Preserve CloudStream plugin API classes under original package names for runtime DexClassLoader resolution
-keep class com.lagradost.cloudstream3.** { *; }
-keep interface com.lagradost.cloudstream3.** { *; }
-keep enum com.lagradost.cloudstream3.** { *; }
-keep class com.lagradost.api.** { *; }
-keep class com.lagradost.nicehttp.** { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-dontwarn com.lagradost.cloudstream3.**
