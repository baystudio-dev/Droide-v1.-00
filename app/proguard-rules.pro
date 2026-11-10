

-keepattributes Signature,InnerClasses,EnclosingMethod
# TranslationBundle uses class names for ResourceBundle lookup and public field names as keys.
# Keep these reflection contracts; ordinary JGit implementation code remains shrinkable.
-keep,allowoptimization class * extends org.eclipse.jgit.nls.TranslationBundle {
    public <init>();
    public java.lang.String *;
}
-dontwarn org.slf4j.**

-keep class com.termux.terminal.JNI { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
