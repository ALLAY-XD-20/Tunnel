# Tunnel - PRIVATE source (Pawan)

Keep this repository private. Customers only get the compiled jar and `release/README.md`.

## Build

JDK 17 + Gradle 8.10.2:

```bash
gradle clean build        # jar -> build/libs/tunnel-<version>.jar
gradle releaseZip         # release package -> build/release/Tunnel-<version>-release.zip
```

`releaseZip` contains the compiled jar (in `plugins/`), `README.md`, `exp.yml` and `LICENSE.txt`. No source code.
If Gradle can't resolve the Lavalink plugin / API versions, copy the exact versions from the official
`lavalink-plugin-template` repo into `build.gradle.kts`.

Edit your built-in nodes in `src/main/kotlin/com/pawan/tunnel/TunnelDefaults.kt` BEFORE building.
Anyone with the jar can read those passwords.

## Release (closed source)

1. Make an empty PUBLIC repo for releases, e.g. `pawan/tunnel-releases`. It holds only jars, no code.
2. In this private repo add the secret `RELEASES_TOKEN` and the variable `RELEASES_REPO` (see `.github/workflows/release.yml`).
3. Push a tag, e.g. `git tag 1.0.0 && git push --tags`. The workflow builds the jar and the release zip and publishes them to the releases repo.
4. Send customers the `Tunnel-1.0.0-release.zip` from that release.

No GitHub Actions? Run `gradle releaseZip` yourself, then create the release in the public repo and attach
`build/libs/tunnel-<version>.jar` and the zip. The jar must be attached, auto-update downloads the first `.jar` asset.

## Auto-update

Tunnel reads `releases/latest` of `autoUpdate.owner/repo`, downloads the attached `.jar`, deletes the old
Tunnel jar(s) and (optionally) restarts. It also cleans old jars and `*.part` / `*.bak` files on every start.
To ship an update, just publish a new release with a higher tag (`1.0.1`).

`source: jitpack` still works for a public code repo (`jitpack.yml` is included), but JitPack cannot build a private repo
without a paid plan, so closed-source releases should use `source: github`.

## Features

Multi-node with failover, 109 built-in providers (`ProviderCatalog.kt`), auto-detect + Advanced Search Driver
(`QueryDetector.kt`, `AdvancedSearchDriver.kt`), self-fetching providers (`NodeManager.kt`), REST endpoints
under `/v4/tunnel/`.

The jar is compiled Kotlin and can still be decompiled by someone determined. Add an obfuscator (ProGuard) if that matters to you.
