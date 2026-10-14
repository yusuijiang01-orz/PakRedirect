# BlackBox sandbox test build

RYLUX includes the BlackBox Reborn core as a pinned Git submodule at
`vendor/BlackBoxReborn` (Apache-2.0; upstream commit `7b86a9d62a974650e0659825758931cd91140827`).
The first integration is deliberately small: initialize the engine, import one APK
through Android's document picker, show virtual apps, and request launch. It does not
include the game-learning agent or promise compatibility with every game.

To build a test APK, run the existing **Build RYLUX APK (release optional)** workflow
from the integration branch and leave **Publish GitHub and Gitee releases** unchecked.
The signed APK is uploaded as a temporary Actions artifact for 14 days, without a
GitHub Release or Gitee synchronization. Artifact access follows this repository's
GitHub access settings; it is not an app-update release.

For a device smoke test, install the artifact over the matching RYLUX signing key,
open **BlackBox 游戏沙箱 · 测试版**, import an APK you are authorized to test, then try
launching it from the sandbox list. Record the Android/emulator version, CPU ABI, game
package name, install result, launch result, and relevant logcat errors. Virtualized
games may still fail because of graphics, native library, Google Play Services,
integrity, or anti-cheat requirements.
