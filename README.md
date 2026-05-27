# MiaHub

MiaHub 是 Mia 系列 Paper 插件的 GitHub 分发与生命周期管理器。当前仓库采用 monorepo：所有 Mia 插件放在同一个 GitHub 仓库里维护，但每个插件拥有独立版本号、独立 GitHub Release 和独立下载资产。

目标服务器环境：

- Minecraft / Paper：`1.21.x`
- Java：`21`
- 构建工具：Gradle `9.5.1`
- 分发平台：[Timefiles404/MiaHub](https://github.com/Timefiles404/MiaHub)

## 当前模块

| 模块 | 插件 | 当前版本 | Release Tag | 说明 |
| --- | --- | --- | --- | --- |
| `miahub` | `MiaHub` | `0.2.7` | `miahub-v0.2.7` | Mia 系列插件管理器，命令 `/miah` |
| `miaforge` | `MiaForge` | `0.2.5` | `miaforge-v0.2.5` | 测试插件，命令 `/miaf reload` |
| `miaskillpool` | `MiaSkillpool` | `0.2.5` | `miaskillpool-v0.2.5` | MythicMobs 技能池插件，命令 `/mias` |

## 命令

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
/miaf reload
/mias
/mias mode <health|rage|mana>
/mias upgrade slot <1-5>
/mias learn <玩家> <skillId>
/mias givebook <玩家> <skillId> [数量]
/mias random <玩家> <roll|enable|disable>
/mias mana addmax <玩家> <数值>
/mias reload
```

MiaSkillpool 运行时依赖 MythicMobs，依赖关系写在 `catalog.json` 的 `dependencies` 中。构建时只使用 `D:\Projects\MiaPlugins\references\MythicMobsPremium-5.12.0-SNAPSHOT.jar` 作为 `compileOnly` 依赖，不会把 MythicMobs 打包进 MiaSkillpool jar。
GitHub Actions 会在构建 `miaskillpool` 前通过私有 HTTPS 下载该 jar，需要仓库 Secrets `MIA_DEPS_URL` 和 `MIA_DEPS_BASIC_AUTH`。

MiaHub 默认优先从下载站 `https://plug.timefiles.online` 下载插件，下载站不可用或 artifact 缺失时回退到 GitHub Releases。模块 tag 发布后，GitHub Actions 会把对应 jar 同步到下载站，需要仓库 Secrets `PLUG_SITE_URL` 和 `PLUG_SITE_UPLOAD_TOKEN`。

`/miah pull` 会从默认 catalog 地址拉取最新可用插件列表：

```text
https://raw.githubusercontent.com/Timefiles404/MiaHub/main/catalog.json
```

## 管理边界

MiaHub 只管理 `catalog.json` 中注册的 Mia 系列插件。`install` 补全会隐藏已安装插件和 MiaHub 自身；`update` 补全只显示已安装且版本落后于 catalog 的插件。`/miah list` 会用树形子行显示 catalog 中声明的前置插件，并检测它们是否已加载或已放入 `plugins/` 目录；安装或更新前置缺失的插件时会在下载前拦截。使用 `/miah install <插件> --deps` 或 `/miah update <插件> --deps` 时，MiaHub 会从 PlugSite 查找缺失的硬依赖，递归检查依赖链，先安装并加载前置插件，再安装目标插件。

MiaHub 会保护自己，不能通过 `/miah install|update|uninstall|enable|disable miahub` 在运行时操作自身。更新 MiaHub 时请手动替换 `MiaHub.jar` 后重启服务器。

传统 Bukkit/Paper `plugin.yml` 插件会尝试运行时加载、卸载、启用和禁用；带 `paper-plugin.yml` 的插件可以安装，但需要重启服务器加载。

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

`catalog.json` 是公开分发索引，也是 MiaHub jar 内置 catalog 的来源。构建 `miahub` 时，Gradle 会把根目录的 `catalog.json` 打进 jar，不再维护第二份资源副本。

## 独立版本

每个插件的版本写在 `gradle.properties`：

```properties
miahub.version=0.2.7
miaforge.version=0.2.5
miaskillpool.version=0.2.5
```

根项目会按模块名读取 `${module}.version`。因此可以只升级 `miaforge`，而让 `miaskillpool` 留在旧版本，用于测试 MiaHub 的单插件更新探测。

## Catalog

每个 catalog 条目对应一个插件的当前最新版：

```json
{
  "id": "miaforge",
  "name": "MiaForge",
  "pluginName": "MiaForge",
  "repository": "Timefiles404/MiaHub",
  "releaseTag": "miaforge-v0.2.5",
  "asset": "MiaForge-0.2.5.jar",
  "fileName": "MiaForge.jar",
  "version": "0.2.5",
  "dependencies": []
}
```

MiaHub 根据 `version` 判断是否可更新，根据 `releaseTag` 和 `asset` 生成 GitHub Release 直链下载地址，根据 `dependencies` 检测安装/更新前置条件。

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

新版本优先使用模块 tag：

```text
miahub-v0.2.7
miaforge-v0.2.5
miaskillpool-v0.2.5
```

GitHub Actions 会解析 tag 中的模块名，校验 tag 版本是否等于 `gradle.properties` 中对应模块版本，只构建该模块，并发布只包含该模块 jar 和 `SHA256SUMS.txt` 的 GitHub Release。

旧的 `v*` 总版本 tag 仍可用，会一次构建并发布所有模块；新 Mia 插件开发默认不要使用这种方式。

## 给 Agent 的流程说明

本仓库内置项目技能：

```text
.codex/skills/mia-series-plugin-dev/SKILL.md
```

后续让其他 agent 开发、升级或发布 Mia 系列插件时，让它先使用 `$mia-series-plugin-dev`。技能里包含新增插件、修改版本、更新 catalog、构建验证、提交 GitHub、打模块 tag、确认 Release 和测试 MiaHub 更新探测的完整流程。
