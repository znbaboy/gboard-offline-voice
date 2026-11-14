package app.template.patches.gboard.voice

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

val COMPATIBILITY_GBOARD = Compatibility(
    name = "Gboard",
    packageName = "com.google.android.inputmethod.latin",
    apkFileType = ApkFileType.APK,
    appIconColor = 0x4285F4,
    targets = listOf(
        AppTarget(version = "18.6.3.1002251336-release-arm64-v8a"),
        AppTarget(version = null, isExperimental = true),
    ),
)
