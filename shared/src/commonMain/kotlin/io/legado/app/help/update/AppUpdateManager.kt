package io.legado.app.help.update

import io.legado.app.help.coroutine.IoDispatcher
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile

/**
 * 检测器配置表 —— 换分发渠道只改这里, 各端与 UI 层都不动。
 *
 * 当前四端一律 GitHub Release 侧载 ([GitHubReleaseChecker]), 不上架任何市场。
 * 未来某端上架时在这里换绑, 例如 iOS 上架 App Store:
 * ```
 * UpdateCheckers.register(UpdatePlatform.IOS, AppStoreChecker(bundleId))
 * ```
 * 鸿蒙同理换 [AppGalleryChecker]。
 */
object UpdateCheckers {

    private val gitHub by lazy { GitHubReleaseChecker() }

    private val overrides = mutableMapOf<UpdatePlatform, UpdateChecker>()

    fun register(platform: UpdatePlatform, checker: UpdateChecker) {
        overrides[platform] = checker
    }

    fun of(platform: UpdatePlatform): UpdateChecker = overrides[platform] ?: gitHub
}

/**
 * 当前端的运行时信息 (平台/版本号/渠道/架构), 由各端启动时注册。
 *
 * 关于页"检查更新"入口以 [AppUpdateManager.isAvailable] 为 gate, 未注册的端不显示入口。
 * 定制 Android 包不注册官方更新环境，避免覆盖迁移功能；desktop 保留自身注册。
 * 未注册环境的端同时隐藏更新渠道和自动检查设置，不发起版本检测。
 */
interface AppUpdateEnvironment {
    val platform: UpdatePlatform
    val currentVersionName: String
    val currentAppVariant: AppVariant get() = AppVariant.OFFICIAL
    val supportedAbis: List<String> get() = listOf("arm64")
    val updateToVariant: String get() = "default_version"
}

/**
 * 检查更新统一入口: 取检测器 → 检测, 平台差异全部收敛在 [UpdateCheckers]。
 *
 * 只负责"查有没有新版本"。拿到新版本后怎么装不再分层 ——
 * 四端一律交 [io.legado.app.model.Download.start] 下载 (对齐原版 UpdateDialog 的
 * menu_download → DownloadService), 下载完成后的安装/定位由各端下载实现自己收尾。
 */
object AppUpdateManager {

    @Volatile
    private var environment: AppUpdateEnvironment? = null

    fun register(env: AppUpdateEnvironment) {
        environment = env
    }

    /** 是否显示"检查更新"入口 (未注册环境的端隐藏)。 */
    fun isAvailable(): Boolean = environment != null

    /**
     * 检测更新 (可从主线程安全调用: 内部切 IO)。
     *
     * 必须切 IO —— 检测器读 response body 是同步 socket 读 (见
     * [GitHubReleaseChecker.fetchRelease] 的 `res.body.text()`), 而
     * `newCallResponse` 的 await 会把结果 resume 回调用方 context;
     * 调用方 (关于页 rememberCoroutineScope / MainActivity lifecycleScope) 都在主线程,
     * 不切就会在 Android 上抛 NetworkOnMainThreadException 并被吞成"检查更新失败"。
     * 原版 AppUpdate.check 走 `Coroutine.async(scope)`, 其 context 默认就是 IoDispatcher。
     */
    suspend fun check(): UpdateCheckResult = withContext(IoDispatcher) {
        val env = environment ?: return@withContext UpdateCheckResult.Failed(
            IllegalStateException("AppUpdateManager 未注册 AppUpdateEnvironment")
        )
        UpdateCheckers.of(env.platform).check(
            UpdateCheckRequest(
                platform = env.platform,
                currentVersionName = env.currentVersionName,
                currentAppVariant = env.currentAppVariant,
                updateToVariant = env.updateToVariant,
                supportedAbis = env.supportedAbis,
            )
        )
    }
}
