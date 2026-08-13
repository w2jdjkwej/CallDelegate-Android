-keepattributes *Annotation*
-keep class com.example.calldelegate.core.ai.rules.** { *; }
-keep class com.example.calldelegate.data.local.** { *; }
# Shizuku identifies a user service by its class name. Keep the Binder entry point stable.
-keep class com.example.calldelegate.telecom.recording.ShizukuCaptureUserService { *; }
-keep class com.example.calldelegate.telecom.recording.IShizukuCaptureService$Stub { *; }
