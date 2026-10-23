package com.mgba.bingbaihanji.platform.android.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import java.io.File

/**
 * 存档根目录与「所有文件访问权限」的辅助入口。
 *
 * 首选公共目录 `/storage/emulated/0/bingbaihanji/mgba/`，在 Android 11+
 * 上需要 MANAGE_EXTERNAL_STORAGE；未授权时退回应用专属外部目录
 * （`Android/data/<包名>/files/saves`），保证游戏仍能正常存档。
 */
object AndroidSaveStorage {

    /** 公共目录中的一级目录名。 */
    private const val PUBLIC_DIR = "bingbaihanji"

    /** 公共目录中的存档目录名。 */
    private const val PUBLIC_SAVE_DIR = "mgba"

    /** 未授权时的应用专属外部目录名。 */
    private const val FALLBACK_DIR = "saves"

    /** 是否已获得公共目录写入权限。 */
    fun canUsePublicRoot(): Boolean = Environment.isExternalStorageManager()

    /** 公共存档根目录：`/storage/emulated/0/bingbaihanji/mgba`。 */
    fun publicRoot(): File =
        File(Environment.getExternalStorageDirectory(), "$PUBLIC_DIR/$PUBLIC_SAVE_DIR")

    /** 未授权时的回退目录，位于应用专属外部存储，无需任何权限。 */
    fun fallbackRoot(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, FALLBACK_DIR)
    }

    /** 当前实际使用的存档根目录。 */
    fun rootDir(context: Context): File =
        if (canUsePublicRoot()) publicRoot() else fallbackRoot(context)

    /** 创建与当前权限状态匹配的存档存储。 */
    fun create(context: Context): FileSaveStorage {
        val appContext = context.applicationContext
        return FileSaveStorage(
            rootDirProvider = { rootDir(appContext) },
            legacyFilesDir = appContext.filesDir,
            fallbackRootProvider = { fallbackRoot(appContext) },
        )
    }

    /**
     * 打开系统的「所有文件访问权限」页面。
     *
     * 少数机型不提供按包名跳转的入口，此时退回权限列表页面。
     *
     * @return 是否成功发起跳转
     */
    fun openAccessSettings(context: Context): Boolean {
        val packageUri = Uri.parse("package:${context.packageName}")
        val direct = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            packageUri,
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(direct)
            true
        } catch (_: Exception) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                true
            } catch (_: Exception) {
                false
            }
        }
    }
}
