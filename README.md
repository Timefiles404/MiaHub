# MiaHub

This monorepo contains Paper 1.21.x / Java 21 plugins for the Mia plugin
family.

- `miahub`: GitHub-backed manager for Mia plugins.
- `miaforge`: Test plugin with `/miaf reload`.
- `miaskillpool`: Test plugin with `/mias reload`.

## Commands

```text
/miah pull
/miah list
/miah install <plugin>
/miah update <plugin>
/miah uninstall <plugin>
/miah enable <plugin>
/miah disable <plugin>
/miaf reload
/mias reload
```

The first version intentionally manages only plugins listed in `catalog.json`.
MiaHub protects itself from live disable/uninstall/update operations; update it
by replacing the jar and restarting the server.

## Catalog

The default catalog URL is:

```text
https://raw.githubusercontent.com/Timefiles404/MiaHub/main/catalog.json
```

Each catalog entry points to a GitHub repository and release asset. `releaseTag`
may be empty, in which case MiaHub uses the repository's latest release.

## Build

```powershell
gradle build
```

The plugin jar is written to `build/libs/`.
Each module writes its jar to `<module>/build/libs/`.

## Release

Push a version tag such as `v0.2.1`. GitHub Actions will build every module jar,
generate `SHA256SUMS.txt`, and publish all files to the GitHub Release.
