# NoteZero R8 rules.
# Room, Compose, Lifecycle and Biometric ship their own consumer rules, so this file
# stays intentionally small: every custom rule is an attack surface.

# Keep our crypto entry points' method names in stack traces for auditability.
-keepnames class com.cipherwell.notezero.crypto.** { *; }

# Do not strip annotations used by Room/Compose at runtime.
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# Belt and braces: never allow network classes into the final dex even if a
# transitive dependency smuggles them in. If the build fails here, a dependency
# broke the offline promise — remove the dependency, do not edit these rules.
-dontwarn java.net.**
-assumenosideeffects class java.net.URLConnection {
    public void connect();
}
