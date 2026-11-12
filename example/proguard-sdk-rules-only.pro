# The release check's R8 run with the SDK's consumer rules alone (-PreceptionExampleSdkRulesOnly=true, see
# build.gradle.kts). Every other library's rules are ignored there, so this file says only what those rules would say
# about classes that are absent on Android or optional: R8 stops on a missing class. The APK of that run is a build
# check, never installed.
-dontwarn android.view.DisplayListCanvas
-dontwarn android.view.HardwareCanvas
-dontwarn android.view.RenderNode
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
