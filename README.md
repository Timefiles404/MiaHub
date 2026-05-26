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

Plugin lifecycle handling follows the same practical boundaries as PlugManX:
traditional Bukkit/Paper plugins can be dynamically loaded and unloaded, while
`paper-plugin.yml` plugins are installed but require a restart.

## Catalog

The default catalog URL is:

```text
https://raw.githubusercontent.com/Timefiles404/MiaHub/main/catalog.json
```

Each catalog entry points to a GitHub repository and release asset. `releaseTag`
may be empty, in which case MiaHub uses the repository's latest release.

## Versioning

This repository is a monorepo, but each plugin owns its own version in
`gradle.properties`.

```properties
miahub.version=0.2.5
miaforge.version=0.2.4
miaskillpool.version=0.2.4
```

Module releases use tags in the form `<module>-v<version>`, for example:

```text
miahub-v0.2.5
miaforge-v0.2.4
miaskillpool-v0.2.4
```

The public `catalog.json` is the source of truth for the latest downloadable
version of each plugin. MiaHub also bundles that same root catalog file at build
time, so there is no second resource copy to keep in sync.

## Build

```powershell
gradle build
```

The plugin jar is written to `build/libs/`.
Each module writes its jar to `<module>/build/libs/`.

## Release

Push a module tag such as `miahub-v0.2.5`. GitHub Actions validates that the tag
matches the module version, builds that module jar, generates `SHA256SUMS.txt`,
and publishes the files to that module release.

The legacy `v*` tag shape still builds and publishes every module together, but
new Mia plugin releases should prefer module tags.
