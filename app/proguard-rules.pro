# Keep source and line info for better Crashlytics stack traces.
-keepattributes SourceFile,LineNumberTable

# Keep Kotlin metadata/annotations used by serialization and some generated code.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Google API Client / Drive — reflection-based JSON mapping over @Key fields
-keepclassmembers class * {
  @com.google.api.client.util.Key <fields>;
}
-keep class com.google.api.services.drive.model.** { *; }
-keep class com.google.api.client.googleapis.** { *; }

# google-http-client references optional deps it doesn't actually need at runtime
-dontwarn com.google.api.client.http.apache.**
-dontwarn org.apache.http.**
-dontwarn javax.annotation.**
-dontwarn com.google.errorprone.annotations.**