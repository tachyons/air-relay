# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class in.aboobacker.airrelay.**$$serializer { *; }
-keepclassmembers class in.aboobacker.airrelay.** { *** Companion; }
-keepclasseswithmembers class in.aboobacker.airrelay.** { kotlinx.serialization.KSerializer serializer(...); }
