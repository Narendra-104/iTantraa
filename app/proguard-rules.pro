# Keep Room entities and DAOs
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }

# Keep ONNX Runtime
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# Keep iTantra models
-keep class org.coresense.itantra.protocol.** { *; }
-keep class org.coresense.itantra.storage.** { *; }
-keep class org.coresense.itantra.emergency.** { *; }
