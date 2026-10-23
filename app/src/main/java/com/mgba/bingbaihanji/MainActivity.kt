package com.mgba.bingbaihanji

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.mgba.bingbaihanji.core.storage.RomCatalog
import com.mgba.bingbaihanji.core.storage.RomLibrary
import com.mgba.bingbaihanji.platform.android.storage.AndroidRomCatalog
import com.mgba.bingbaihanji.platform.android.storage.AndroidSaveStorage
import com.mgba.bingbaihanji.ui.GameListScreen
import com.mgba.bingbaihanji.ui.SettingsNav
import com.mgba.bingbaihanji.ui.settings.LocalKeyMapStore
import com.mgba.bingbaihanji.ui.settings.LocalShaderStore
import com.mgba.bingbaihanji.ui.settings.LocalSettingsStore
import com.mgba.bingbaihanji.ui.theme.MgbaAndroidTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 主界面：游戏列表与设置入口。
 */
class MainActivity : ComponentActivity() {

    /** 由 Application 组合根提供的设置存储。 */
    private val settingsStore by lazy { (application as MgbaApplication).settings }

    /** 由 Application 组合根提供的着色器配置存储。 */
    private val shaderStore by lazy { (application as MgbaApplication).shaderStore }

    /** 由 Application 组合根提供的按键映射存储。 */
    private val keyMapStore by lazy { (application as MgbaApplication).keyMapStore }

    /** ROM 目录，游戏列表与删除共用同一个实例。 */
    private val romCatalog by lazy { AndroidRomCatalog(this) }

    /** ROM 删除编排：ROM 副本 + 存档 + 金手指。 */
    private val romLibrary by lazy {
        RomLibrary(
            catalog = romCatalog,
            saveStorage = AndroidSaveStorage.create(this),
            cheatStore = (application as MgbaApplication).cheatStore,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MgbaAndroidTheme {
                // 平台层在这里注入设置实现，UI 只依赖 core 的接口。
                CompositionLocalProvider(
                    LocalSettingsStore provides settingsStore,
                    LocalShaderStore provides shaderStore,
                    LocalKeyMapStore provides keyMapStore,
                ) {
                    AppNavigation(romCatalog = romCatalog, romLibrary = romLibrary)
                }
            }
        }
    }
}

private sealed interface Screen {
    object GameList : Screen
    object Settings : Screen
}

@Composable
private fun AppNavigation(
    romCatalog: RomCatalog,
    romLibrary: RomLibrary,
) {
    var screen by remember { mutableStateOf<Screen>(Screen.GameList) }
    val context = LocalContext.current

    // Android 11+ 写入公共目录需要“所有文件访问权限”；
    // 未授权时先提示一次，并说明会退回应用专属目录。
    var showStorageAccessDialog by remember {
        mutableStateOf(!AndroidSaveStorage.canUsePublicRoot())
    }

    if (showStorageAccessDialog) {
        AlertDialog(
            onDismissRequest = { showStorageAccessDialog = false },
            title = { Text("存档目录权限") },
            text = {
                Text(
                    "存档将写入 /storage/emulated/0/bingbaihanji/mgba/。\n" +
                            "请授予“所有文件访问权限”。未授权时会临时使用应用专属目录，"
                            + "授权后新建的游戏会直接写入公共目录。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showStorageAccessDialog = false
                        AndroidSaveStorage.openAccessSettings(context)
                    }
                ) {
                    Text("去授权")
                }
            },
            dismissButton = {
                TextButton(onClick = { showStorageAccessDialog = false }) {
                    Text("稍后")
                }
            },
        )
    }

    val romPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            (context as? ComponentActivity)?.startActivity(
                GameActivity.createIntent(context, uri)
            )
        }
    }

    when (screen) {
        Screen.GameList -> GameListScreen(
            romCatalog = romCatalog,
            onPlayRom = { path ->
                (context as? ComponentActivity)?.startActivity(
                    GameActivity.createIntent(context, path)
                )
            },
            onOpenSettings = { screen = Screen.Settings },
            onPickRom = { romPicker.launch(arrayOf("*/*")) },
            onDeleteRom = { rom, deleteSaves ->
                withContext(Dispatchers.IO) {
                    romLibrary.deleteRom(rom.id, deleteSaves)
                }
            },
        )

        Screen.Settings -> SettingsNav(
            onBack = { screen = Screen.GameList },
        )
    }
}
