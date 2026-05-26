# MiaHub

MiaHub is a Paper 1.21.x / Java 21 plugin manager for the Mia plugin family.
It reads a GitHub-hosted catalog, downloads plugin jars from GitHub Releases,
and manages installed Mia plugins with `/miah`.

## Commands

```text
/miah pull
/miah list
/miah install <plugin>
/miah update <plugin>
/miah uninstall <plugin>
/miah enable <plugin>
/miah disable <plugin>
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

## Release

Push a version tag such as `v0.1.0`. GitHub Actions will build the jar,
generate `SHA256SUMS.txt`, and publish both files to the GitHub Release.
