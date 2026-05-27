---
name: mia-series-plugin-dev
description: Develop, update, release, and register Mia series Paper plugins in the MiaHub monorepo. Use when adding a Mia plugin, changing independent module versions, updating catalog.json, building Java 21 Paper 1.21.x jars, pushing to GitHub, creating module release tags, confirming GitHub Releases, or validating MiaHub install/update/list behavior.
---

# Mia Series Plugin Dev

Use this skill when working in the `Timefiles404/MiaHub` monorepo. The repository contains Java 21 / Paper 1.21.x plugins managed by MiaHub. Keep the monorepo, but treat each plugin as an independently versioned and independently released module.

## Ground Rules

- Work from `D:\Projects\MiaPlugins\MiaHub` unless the user gives another path.
- Preserve unrelated user changes. Check `git status --short --branch` before editing and before committing.
- Do not print, commit, or store tokens. Use the user environment variable `MIAHUB_TOKEN` only through temporary git or `gh` environment variables.
- Use `gradle.properties` as the source of module versions.
- Use the root `catalog.json` as the only catalog file. `miahub/build.gradle.kts` packages this file into the MiaHub jar.
- Use module tags for new releases: `<module>-v<version>`, for example `miaforge-v0.2.5`.
- Avoid legacy `v*` tags unless the user explicitly wants one release containing every module.
- MiaHub protects itself at runtime. Updating MiaHub means replacing `MiaHub.jar` and restarting the server.
- Treat user-visible plugin updates as releasable by default. Unless the user explicitly asks to leave changes local, every code/config/catalog change that should be installable or detectable by MiaHub must include the module version bump, root `catalog.json` update, local build, jar metadata inspection, commit, module tag, push, GitHub Actions watch, and GitHub Release asset confirmation in the same workflow.
- If the user says "update", "修改", "实现", "修复", or asks whether MiaHub can detect an update, assume a patch release is required for the touched module unless they explicitly say "do not release", "local only", or provide a different version.

## Repository Map

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
└─ miaskillpool/
```

Current module version keys:

```properties
miahub.version=0.2.6
miaforge.version=0.2.5
miaskillpool.version=0.2.5
```

## Local Build Setup

In PowerShell, set Java and Gradle for the current process before building:

```powershell
$env:JAVA_HOME='D:\Develop\Java\JDK21'
$env:Path='D:\Develop\Java\JDK21\bin;D:\Develop\Gradle\bin;' + $env:Path
gradle clean build
```

Expected jars:

```text
miahub/build/libs/MiaHub-<version>.jar
miaforge/build/libs/MiaForge-<version>.jar
miaskillpool/build/libs/MiaSkillpool-<version>.jar
```

## Updating Or Releasing One Plugin

When changing a plugin in a way that should reach servers through MiaHub, do the full release flow without waiting for a separate user prompt:

1. Edit only the relevant `<module>.version` in `gradle.properties`.
2. Update that plugin's entry in root `catalog.json`:
   - `releaseTag`: `<module>-v<version>`
   - `asset`: `<PluginName>-<version>.jar`
   - `version`: `<version>`
3. If README examples list current versions, update them too.
4. Run `gradle clean build`.
5. Inspect the built jar `plugin.yml` and confirm the intended module has the new version and required runtime dependencies.
6. Commit the implementation, version, catalog, README, and skill changes together unless there is a clear reason to split commits.
7. Create the matching module tag on the commit.
8. Push `main` and the module tag.
9. Watch the GitHub Actions run for the pushed tag.
10. Confirm the GitHub Release exists and contains exactly the intended module jar plus `SHA256SUMS.txt`.
11. If practical, validate MiaHub update detection with `/miah pull`, `/miah list`, and `/miah update <TAB>`.

Example for MiaForge `0.2.6`:

```powershell
git add gradle.properties catalog.json README.md
git commit -m "Bump MiaForge to 0.2.6"
git tag miaforge-v0.2.6
```

## Adding A New Mia Plugin

1. Add the module directory, for example `miacore/`.
2. Add `include("miacore")` to `settings.gradle.kts`.
3. Add `miacore.version=<initial-version>` to `gradle.properties`.
4. Create `miacore/build.gradle.kts` and set the jar base name:

```kotlin
tasks.jar {
    archiveBaseName.set("MiaCore")
}
```

5. Create `src/main/resources/plugin.yml` with `version: ${version}`, `api-version: "1.21"`, commands, permissions, and the Java main class.
6. Create the plugin main class under `src/main/java/dev/timefiles/<module>/`.
7. Register the plugin in root `catalog.json`.
8. Build, inspect the jar metadata, commit, push, tag, and confirm the module release.

Keep skeleton plugins minimal but real: register commands, permissions, and at least a reload command if the user wants a test plugin.

## Catalog Requirements

Every managed plugin needs a catalog entry with these fields:

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
  "minecraft": "1.21.x",
  "java": 21,
  "restartRequired": false,
  "dependencies": []
}
```

MiaHub uses `version` for update detection, `fileName` for the installed jar name, `releaseTag + asset` for direct GitHub Release downloads, and `dependencies` for runtime prerequisite checks in `/miah list`, `install`, and `update`. Keep `id` lowercase and put external Paper plugin dependencies there by plugin name, for example `["MythicMobs"]`.

## Release Workflow

After committing, push `main` and the module tag using `MIAHUB_TOKEN` without printing it:

```powershell
$token = [Environment]::GetEnvironmentVariable('MIAHUB_TOKEN','User')
$basic = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes('x-access-token:' + $token))
$env:GIT_TERMINAL_PROMPT = '0'
git -c http.https://github.com/.extraheader="AUTHORIZATION: basic $basic" push origin main
git -c http.https://github.com/.extraheader="AUTHORIZATION: basic $basic" push origin <module>-v<version>
```

Watch the GitHub Actions run:

```powershell
$env:GH_TOKEN = [Environment]::GetEnvironmentVariable('MIAHUB_TOKEN','User')
gh run list --repo Timefiles404/MiaHub --limit 8 --json databaseId,headBranch,status,conclusion,displayTitle
gh run watch <run-id> --repo Timefiles404/MiaHub --exit-status
```

Confirm the release assets:

```powershell
$env:GH_TOKEN = [Environment]::GetEnvironmentVariable('MIAHUB_TOKEN','User')
gh release view <module>-v<version> --repo Timefiles404/MiaHub --json assets,url
```

For a module release, the assets should be only:

```text
<PluginName>-<version>.jar
SHA256SUMS.txt
```

## Update Detection Test

Use the local Paper server at `D:\Projects\MiaPlugins\PaperServer-1.21.4` when available.

To test a single-plugin update:

1. Leave the target plugin jar on an older version in `plugins/`.
2. Ensure `catalog.json` points only that plugin to a newer version.
3. Replace `plugins/MiaHub.jar` with the latest local MiaHub jar if MiaHub itself changed.
4. Restart the Paper server when MiaHub changed.
5. In console or game, run:

```text
/miah pull
/miah list
/miah update <TAB>
```

Expected behavior: only installed plugins whose installed version differs from catalog `version` should appear in `update` tab completion. After `/miah update <plugin>` succeeds, that plugin should disappear from update completion.

## Final Checks

Before reporting done:

- `git status --short --branch` is clean unless the user asked to leave changes local.
- `gradle clean build` passes.
- The intended jar has the expected `plugin.yml` version.
- The GitHub Release exists and contains the intended module jar.
- If MiaHub runtime behavior changed, explain whether the test server needs a restart.
