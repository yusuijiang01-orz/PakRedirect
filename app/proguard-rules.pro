# Payment SDK entry points and reflection classes.
-keep class com.alipay.** { *; }
-keep class com.tencent.mm.opensdk.** { *; }
-keep class com.example.pakredirect.wxapi.** { *; }
-dontwarn com.alipay.**
-keep class org.amnezia.awg.hevtunnel.TProxyService { *; }

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
