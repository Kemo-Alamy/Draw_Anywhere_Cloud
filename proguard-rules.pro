# DrawAnywhere ProGuard Rules

# ─── Keep Application Classes ────────────────────────────────────────────────
-keep class com.drawanywhere.app.** { *; }

# ─── Keep Android Components ─────────────────────────────────────────────────
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.view.View

# ─── Keep Custom Views (مهم عشان HsvColorPickerView يتحمّل من الـ XML) ───────
-keep class com.drawanywhere.app.HsvColorPickerView { *; }
-keep class com.drawanywhere.app.DrawingCanvas { *; }

# ─── Kotlin ───────────────────────────────────────────────────────────────────
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
-dontwarn kotlin.**

# ─── AndroidX / Support ──────────────────────────────────────────────────────
-keep class androidx.** { *; }
-dontwarn androidx.**

# ─── Material Components ─────────────────────────────────────────────────────
-keep class com.google.android.material.** { *; }
-dontwarn com.google.android.material.**

# ─── Suppress Common Warnings ────────────────────────────────────────────────
-dontwarn org.xmlpull.v1.**
-dontwarn javax.annotation.**
