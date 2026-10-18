# Recovered Lua regression tests

Run the recovered LuaJ task tests without configuring the Android application or building an APK:

```powershell
gradle -p tools/lua-regression test
```

The runner compiles the pure-Java Lua compatibility helpers, recovered-script tests, Lua pause/stop control tests, and screenshot fixtures. It does not apply the Android Gradle Plugin or build/install an APK.

These tests execute recovered Lua functions against controlled host callbacks and captured game screenshots. They verify script decisions and emitted actions in the test harness; they do not prove that Android OCR, screenshot capture, touch injection, sandbox launch, or the full game loop works on a device.
