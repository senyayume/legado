# Android 构建说明

使用 JDK 21、Android SDK 和仓库内的 Gradle Wrapper。SDK 位置使用本机 `local.properties` 或 `ANDROID_HOME`，不要提交本机路径或签名密钥。

## 构建

Windows PowerShell：

```powershell
./gradlew.bat :app:assembleAppRelease :app:lintAppRelease --console=plain
```

其他系统使用 `./gradlew`。APK 位于 `app/build/outputs/apk/app/release/`，按设备选择 `arm64-v8a`、`armeabi-v7a` 或 universal 产物。

标准 release 安装身份为 `shutiao.reader.release`；使用 `-PcoexistBuild=true` 时为 `shutiao.reader.releaseA`。debug 安装身份为 `shutiao.reader.debug`。不同身份的数据相互独立。

未配置发布签名时，本地 release 使用调试签名。正式签名通过现有 `RELEASE_STORE_FILE`、`RELEASE_STORE_PASSWORD`、`RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD` 构建属性提供；同一安装身份必须保持签名一致才能覆盖升级。

本分支在 `settings.gradle.kts` 按 [R8 官方覆盖方式](https://r8.googlesource.com/r8.git/#replacing-r8-in-android-gradle-plugin)使用 R8 9.1.31，保留 AGP 8.13.2 和 Kotlin。该版本包含计算树哈希缓存修复，避免原内嵌 R8 8.13.19 在发布优化时出现长时间递归计算。不要通过关闭发布优化来交付性能包。

## 验证

```powershell
./gradlew.bat :shared:jvmTest --console=plain
./gradlew.bat :app:assembleAppDebug :app:assembleAppDebugAndroidTest :app:lintAppDebug --console=plain
```

本机如果已实际确认桌面 QuickJS 原生构建缺少所需 pthread 工具链，可单独验证阅读逻辑：

```powershell
./gradlew.bat :shared:jvmTest -x :modules:quickjs:buildJvmNativeLib --console=plain
```

这一排除只覆盖 JVM 原生任务，不能据此宣称桌面发布构建通过；Android release 构建仍需正常构建 Android 原生库。

阅读相关仪器测试包含 `ReaderCanvasUiTest`、`ReaderBackgroundCacheTest`、`ReaderPaletteUiTest`、`ReaderRulesUiTest`、`ReaderDecorationIntegrationTest`，Android 更新能力策略由 `CustomAppUpdatePolicyTest` 验证。请针对调试构建运行仪器测试；release 裁剪可能移除测试宿主依赖，不能混用两类测试包并把宿主启动失败当成产品崩溃。

真机验收至少检查：新安装默认样式、已有配置的保留与恢复预设、背景/高亮保存和重启、关于页及其它设置无官方更新入口、真实开书/退出/翻页。

性能对照应使用同设备、同书籍和同外观，记录预编译状态，确认手势符合当前翻页模式且页码确实变化。零帧或人工同时操作的样本应剔除；功能测试通过不等于流畅度通过。
