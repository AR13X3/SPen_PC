# Keep the Samsung S Pen Remote SDK classes (it ships as a plain jar with no
# consumer-proguard-rules bundled in, so R8 can strip AIDL-generated classes it
# thinks are unused).
-keep class com.samsung.android.sdk.penremote.** { *; }
