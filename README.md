# PacilRead Mobile

PacilRead 的纯原生 Android 版本，包名为 `com.metahumanz.pacilread`。

构建统一使用原生 `Gradle Wrapper`。

## 功能概览

- 本地书架
- TXT / EPUB / PDF 导入
- 自定义封面
- 阅读页分页显示
- 目录与全文搜索
- 替换规则系统
- 自定义主题与背景图
- 多种翻页动画
- 自动翻页
- Android 原生 TTS / 小米 MiMo / 微软 Edge 听书
- WebDAV 进度同步
- WebDAV 全量 / 增量备份恢复
- 浅色 / 深色资源适配

## 环境要求

- JDK 17
- Android SDK
- Windows 命令行环境

JDK 建议优先通过 `JAVA_HOME` 配置；如果没有配置，仓库根目录的 `pack.bat` 会自动尝试探测以下位置：

- `C:\Program Files\Java\jdk-17*`
- `C:\Program Files\Microsoft\jdk-17*`
- `C:\Program Files\Java\jdk*`
- `C:\Program Files\Microsoft\jdk*`
- `C:\Program Files\Android\Android Studio\jbr`
- `PATH` 里的 `java.exe`

Android SDK 路径请按本地环境自行配置，`local.properties` 不纳入版本控制。

`local.properties` 放在仓库根目录，最小内容示例：

```properties
sdk.dir=C\:\\Android\\SDK
```

如果你的 SDK 在别的位置，把路径改成自己的即可。

如果你是在这台 Windows 机器上直接使用仓库根目录的 `pack.bat`，脚本会优先尝试自动探测以下位置并生成 `local.properties`：

- `ANDROID_HOME`
- `ANDROID_SDK_ROOT`
- `C:\Android\Sdk`
- `C:\Android\SDK`
- `%LOCALAPPDATA%\Android\Sdk`

## 开发

### 常用命令

这些命令都在仓库根目录执行。

调试包：

```powershell
.\gradlew.bat assembleDebug --no-daemon --console plain
```

发布 APK：

```powershell
.\gradlew.bat assembleRelease --no-daemon --console plain
```

发布 AAB：

```powershell
.\gradlew.bat bundleRelease --no-daemon --console plain
```

安装调试包到已连接设备：

```powershell
.\gradlew.bat installDebug --no-daemon --console plain
```

### 快捷命令

仓库根目录提供了一个 `pack.bat`，方便用更短的命令打包：

```powershell
.\pack.bat
.\pack.bat debug
.\pack.bat release
.\pack.bat bundle
.\pack.bat install
.\pack.bat clean
```

对应关系：

- `debug` -> `assembleDebug`
- `release` -> `assembleRelease`
- `bundle` -> `bundleRelease`
- `install` -> `installDebug`
- `clean` -> `clean`

### 构建产物

调试 APK 默认输出到：

- `app/build/outputs/apk/debug/app-debug.apk`

发布产物默认输出到：

- `app/build/outputs/apk/release/`
- `app/build/outputs/bundle/release/`

## 听书说明

- 阅读页听书面板支持 `本地系统 TTS`、`小米 MiMo` 和 `微软 Edge` 三种引擎。
- **本地系统 TTS**：使用 Android 设备自带的语音合成服务，无需联网。
- **小米 MiMo**：需要配置 API Key。可以在阅读页听书面板或设置页中填写。实现类为 `com.metahumanz.pacilread.tts.MimoTtsClient`。
- **微软 Edge**：基于微软 Azure 语音服务，提供自然流畅的云端语音。无需额外配置，但需要网络连接。实现类为 `com.metahumanz.pacilread.tts.EdgeTtsClient`。
- **分句逻辑**：所有引擎均采用与 Win11 版一致的分句正则，确保跨平台听书节奏统一。

## 项目结构

- `app/` Android 应用主模块
- `app/src/main/java/` Java 源码根目录，主包声明统一为 `com.metahumanz.pacilread`
- `app/src/main/res/` 资源文件
