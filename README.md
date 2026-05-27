# MiaHub

MiaHub 是 Mia 系列 Paper 插件的分发与生命周期管理器。它负责读取公开 catalog、检测版本、安装/更新插件，并优先从 PlugSite 下载站获取 jar，下载站不可用时回退到 GitHub Releases。

目标运行环境：

- Minecraft / Paper：`1.21.x`
- Java：`21`
- 构建工具：Gradle `9.5.1`
- 分发仓库：[Timefiles404/MiaHub](https://github.com/Timefiles404/MiaHub)
- 下载站：`https://plug.timefiles.online`

## MiaHub 命令

```text
/miah help
/miah pull
/miah list
/miah install <插件>
/miah install <插件> --deps
/miah update <插件>
/miah update <插件> --deps
/miah uninstall <插件>
/miah enable <插件>
/miah disable <插件>
```

`/miah pull` 会从默认 catalog 地址拉取最新插件列表：

```text
https://raw.githubusercontent.com/Timefiles404/MiaHub/main/catalog.json
```

`/miah list` 会显示 catalog 中插件的安装状态、版本状态和硬依赖状态。安装或更新时，如果硬依赖缺失，MiaHub 默认会拦截；管理员可以使用 `--deps` 从 PlugSite 自动补齐已登记且允许自动安装的依赖插件。

## 下载源

MiaHub 默认按以下顺序下载 Mia 插件：

1. PlugSite：`https://plug.timefiles.online`
2. GitHub Release：`releaseTag + asset`

依赖插件 jar 只从 PlugSite 获取，并由 PlugSite 读取 jar 内的 `plugin.yml` 或 `paper-plugin.yml` 归一化保存真实插件名、版本、硬依赖、软依赖和 SHA-256。受保护依赖下载需要在服务器的 `plugins/MiaHub/config.yml` 中配置 `download-site.token`。

## 管理边界

MiaHub 只管理 `catalog.json` 中注册的插件。MiaHub 会保护自己，不能通过 `/miah install|update|uninstall|enable|disable miahub` 在运行时操作自身。更新 MiaHub 时需要手动替换 `MiaHub.jar` 并重启服务器。

传统 Bukkit/Paper `plugin.yml` 插件会尝试运行时加载、卸载、启用和禁用；带 `paper-plugin.yml` 的插件可以安装，但需要重启服务器加载。

## 子插件文档

仓库总 README 只描述 MiaHub。Mia 系列子插件的用户文档以 `wiki.html` 形式打包进各自 jar，插件首次启动释放到自己的插件数据目录，例如：

```text
plugins/<插件名>/wiki.html
```

## 仓库结构

```text
MiaHub/
├─ .github/workflows/build.yml
├─ .codex/skills/mia-series-plugin-dev/SKILL.md
├─ catalog.json
├─ gradle.properties
├─ settings.gradle.kts
├─ build.gradle.kts
├─ miahub/
├─ miaforge/
├─ miaskillpool/
└─ plugsite/
```

`catalog.json` 是公开分发索引，也是 MiaHub jar 内置 catalog 的来源。构建 `miahub` 时，Gradle 会把根目录的 `catalog.json` 打进 jar。

## 本地构建

PowerShell 示例：

```powershell
cd D:\Projects\MiaPlugins\MiaHub
$env:JAVA_HOME='D:\Develop\Java\JDK21'
$env:Path='D:\Develop\Java\JDK21\bin;D:\Develop\Gradle\bin;' + $env:Path
gradle clean build
```

构建产物位于：

```text
miahub/build/libs/MiaHub-<version>.jar
miaforge/build/libs/MiaForge-<version>.jar
miaskillpool/build/libs/MiaSkillpool-<version>.jar
```

## 发布流程

每个插件独立版本、独立 tag、独立 GitHub Release。版本写在 `gradle.properties`，tag 格式为：

```text
<module>-v<version>
```

GitHub Actions 会解析 tag 中的模块名，只构建对应模块，发布该模块 jar 和 `SHA256SUMS.txt`，并把模块 jar 同步到 PlugSite。

CI 编译依赖由子模块的 `compile-dependencies.json` 声明插件名。工作流会按插件名向 PlugSite 查询依赖 artifact，使用 PlugSite 返回的真实 `pluginName` 和 SHA-256 下载到 `../references/<pluginName>.jar`，不再依赖带版本号或渠道后缀的固定 jar 文件名。
