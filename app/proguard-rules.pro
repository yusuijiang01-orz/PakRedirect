# Payment SDK entry points and reflection classes.
-keep class com.alipay.** { *; }
-keep class com.tencent.mm.opensdk.** { *; }
-keep class com.example.pakredirect.wxapi.** { *; }
-dontwarn com.alipay.**
-keep class org.amnezia.awg.hevtunnel.TProxyService { *; }

# LuaJ loads standard libraries and portions of its bytecode runtime by class name.
# Keep the complete interpreter intact in the minified release APK; R8 otherwise
# removes inner library implementations that appear unused from Java call sites.
-keep class org.luaj.** { *; }
# LuaJ ships optional adapters for the desktop JSR-223 scripting API and its
# bytecode compiler. RYLUX uses the interpreter only; those APIs do not exist
# on Android and are never loaded by the script host.
-dontwarn javax.script.**
-dontwarn org.apache.bcel.**

# BlackBox creates Android framework proxies from runtime annotations on
# generated black.android.* wrappers. Preserve their annotations and members
# through the host app's R8 pass (the library consumer rules don't cover them).
-keepattributes *Annotation*
-keep class black.** { *; }
-keep class top.niunaijun.blackreflection.** { *; }
-keep @top.niunaijun.blackreflection.annotation.BClass class * { *; }
-keep @top.niunaijun.blackreflection.annotation.BClassName class * { *; }
-keep @top.niunaijun.blackreflection.annotation.BClassNameNotProcess class * { *; }
-keepclasseswithmembernames class * {
    @top.niunaijun.blackreflection.annotation.BField.* <methods>;
    @top.niunaijun.blackreflection.annotation.BFieldNotProcess.* <methods>;
    @top.niunaijun.blackreflection.annotation.BFieldSetNotProcess.* <methods>;
    @top.niunaijun.blackreflection.annotation.BFieldCheckNotProcess.* <methods>;
    @top.niunaijun.blackreflection.annotation.BMethod.* <methods>;
    @top.niunaijun.blackreflection.annotation.BStaticField.* <methods>;
    @top.niunaijun.blackreflection.annotation.BStaticMethod.* <methods>;
    @top.niunaijun.blackreflection.annotation.BMethodCheckNotProcess.* <methods>;
    @top.niunaijun.blackreflection.annotation.BConstructor.* <methods>;
    @top.niunaijun.blackreflection.annotation.BConstructorNotProcess.* <methods>;
}
