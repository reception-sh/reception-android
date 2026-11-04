# Applied to host apps that shrink with R8. The SDK needs no host edits.

# Public API: hosts and the Flutter/React Native bridges call it by name.
-keep public class sh.reception.** { public protected *; }

# kotlinx-serialization: keep generated serializers of the SDK's wire models.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class sh.reception.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class sh.reception.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class sh.reception.**$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class sh.reception.**
-keepclassmembers class <1>$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}

# Firebase Messaging and Play Billing are optional: the SDK checks for them at run time.
-dontwarn com.google.firebase.messaging.**
-dontwarn com.google.firebase.FirebaseApp
-dontwarn com.google.android.gms.tasks.**
-dontwarn com.android.billingclient.api.**

# The per-app locale is read from AppCompat by name when the host ships it (below Android 13).
-keepclassmembers class androidx.appcompat.app.AppCompatDelegate {
    public static *** getApplicationLocales();
}
-keepclassmembers class androidx.core.os.LocaleListCompat {
    public java.lang.String toLanguageTags();
}
-dontwarn androidx.appcompat.app.AppCompatDelegate

# The embedded chat recognizes a Compose Dialog's window by its content view's class (ui/EmbeddedKeyboardMode).
-keepnames class androidx.compose.ui.window.DialogLayout
