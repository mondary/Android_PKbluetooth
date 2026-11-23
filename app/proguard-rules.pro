# Keep Lottie models and animation classes
-keep class com.airbnb.lottie.** { *; }
-dontwarn com.airbnb.lottie.**

# PopupConfig and DeviceBatteryState cross activities as Serializable.
-keep class com.vibepop.data.model.** { *; }
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

-dontwarn javax.annotation.Nullable
