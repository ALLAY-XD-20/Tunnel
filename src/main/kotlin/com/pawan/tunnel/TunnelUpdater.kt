package com.pawan.tunnel

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.InitializingBean
import org.springframework.stereotype.Service
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * Auto-update.
 *
 * Every `checkIntervalMinutes` it asks GitHub for the latest release of owner/repo.
 * If the tag is newer than the running version it:
 *   1. downloads the new jar (attached to the GitHub release, or built by JitPack),
 *   2. changes the Tunnel version line in application.yml, if Tunnel is listed there as a dependency,
 *   3. DELETES the old Tunnel jar(s), so Lavalink never loads two copies,
 *   4. optionally exits Lavalink so your process manager starts the new version.
 * On every start it also removes leftover old Tunnel jars and *.part / *.bak files.
 */
@Service
class TunnelUpdater(private val props: TunnelProperties) : InitializingBean {

    private enum class ConfigState { UPDATED, NOT_MANAGED, FAILED }

    private val log = LoggerFactory.getLogger(TunnelUpdater::class.java)
    private val mapper = ObjectMapper()
    private val http: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    val currentVersion: String =
        TunnelUpdater::class.java.`package`?.implementationVersion ?: TunnelDefaults.VERSION

    @Volatile
    var pendingVersion: String? = null
        private set

    @Volatile
    var lastResult: String = "not checked yet"
        private set

    override fun afterPropertiesSet() {
        val cfg = props.autoUpdate
        if (!cfg.enabled) {
            log.info("[Tunnel] auto-update is disabled")
            return
        }

        if (cfg.deleteOldVersions) {
            runCatching { cleanupStale(cfg) }.onFailure { log.warn("[Tunnel] cleanup failed: {}", it.message) }
        }

        val every = cfg.checkIntervalMinutes.coerceAtLeast(5)
        // First check one minute after startup, so Lavalink finishes booting first
        TunnelExecutors.scheduler.scheduleWithFixedDelay({
            runCatching { checkNow() }.onFailure { log.warn("[Tunnel] update check crashed: {}", it.message) }
        }, 1, every, TimeUnit.MINUTES)
    }

    @Synchronized
    fun checkNow(): String {
        val result = try {
            doCheck(props.autoUpdate)
        } catch (e: Exception) {
            "update check failed: ${e.message}"
        }
        lastResult = result
        log.info("[Tunnel] {}", result)
        return result
    }

    private fun artifactName(cfg: AutoUpdateConfig): String =
        cfg.artifact.ifBlank { if (cfg.source.equals("jitpack", true)) cfg.repo else "tunnel" }

    private fun doCheck(cfg: AutoUpdateConfig): String {
        val safe = Regex("^[A-Za-z0-9._-]+$")
        val artifact = artifactName(cfg)
        if (!safe.matches(cfg.owner) || !safe.matches(cfg.repo) || !safe.matches(artifact)) {
            return "invalid autoUpdate owner/repo/artifact"
        }

        // 1. latest release
        val api = HttpRequest.newBuilder(URI.create("https://api.github.com/repos/${cfg.owner}/${cfg.repo}/releases/latest"))
            .timeout(Duration.ofSeconds(15))
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Tunnel-Updater")
            .GET()
            .build()
        val release = http.send(api, HttpResponse.BodyHandlers.ofString())
        if (release.statusCode() != 200) return "no GitHub release found (HTTP ${release.statusCode()})"

        val json = mapper.readTree(release.body())
        val tag = json.path("tag_name").asText("")
        if (tag.isBlank() || !safe.matches(tag)) return "latest release has no usable tag"

        val latest = tag.removePrefix("v")
        if (!isNewer(latest, currentVersion)) return "up to date ($currentVersion)"
        if (latest == pendingVersion) return "update $latest already downloaded, restart Lavalink to apply it"

        // 2. where the jar comes from
        val jarName = "$artifact-$tag.jar"
        val url: String = if (cfg.source.equals("jitpack", true)) {
            "https://jitpack.io/com/github/${cfg.owner}/${cfg.repo}/$tag/$jarName"
        } else {
            val jars = json.path("assets").filter { it.path("name").asText("").endsWith(".jar") }
            val asset = jars.firstOrNull { it.path("name").asText("").startsWith(artifact) } ?: jars.firstOrNull()
                ?: return "release $tag has no .jar file attached"
            val link = asset.path("browser_download_url").asText("")
            if (!link.startsWith("https://github.com/")) return "release $tag has an unexpected download link"
            link
        }

        // 3. download
        val dir = Paths.get(cfg.pluginsDir)
        Files.createDirectories(dir)
        val part = dir.resolve("$jarName.part")
        Files.deleteIfExists(part)

        val download = http.send(
            HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(3))
                .header("User-Agent", "Tunnel-Updater")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofFile(part)
        )
        if (download.statusCode() != 200) {
            Files.deleteIfExists(part)
            return "download for $tag failed (HTTP ${download.statusCode()}), will retry later"
        }

        if (!looksLikeTunnel(part)) {
            Files.deleteIfExists(part)
            return "downloaded file for $tag is not a Tunnel jar, ignored"
        }

        val target = dir.resolve(jarName)
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING)

        // 4. point application.yml at the new version (before touching the old jar)
        val (state, note) = updateConfig(cfg, artifact, tag)
        if (state == ConfigState.FAILED) {
            Files.deleteIfExists(target)
            return "update to $tag cancelled, could not change application.yml: $note"
        }

        // 5. remove the old version(s)
        removeOldJars(dir, target, artifact, cfg.deleteOldVersions)
        pendingVersion = latest

        val done = "updated $currentVersion -> $latest ($note)"
        if (cfg.restartAfterUpdate) {
            val delay = cfg.restartDelaySeconds.coerceAtLeast(5)
            TunnelExecutors.scheduler.schedule(Runnable {
                log.warn("[Tunnel] exiting Lavalink now to load Tunnel {}", latest)
                System.exit(0)
            }, delay, TimeUnit.SECONDS)
            return "$done, Lavalink exits in ${delay}s to load it"
        }
        return "$done, restart Lavalink to apply it"
    }

    /**
     * Changes `dependency: "com.github.owner:artifact:OLD"` to the new tag, leaving the rest of the
     * file (comments, order, other plugins) untouched. A backup is kept as application.yml.tunnel.bak.
     * Does nothing when Tunnel is installed as a plain jar in plugins/.
     */
    private fun updateConfig(cfg: AutoUpdateConfig, artifact: String, tag: String): Pair<ConfigState, String> {
        if (!cfg.updateConfigFile) return ConfigState.NOT_MANAGED to "application.yml left alone"

        val file = Paths.get(cfg.configFile)
        if (!Files.isRegularFile(file)) return ConfigState.NOT_MANAGED to "no application.yml at $file"

        try {
            val text = Files.readString(file)
            val pattern = Regex(
                "(dependency:\\s*[\"']?com\\.github\\.${Regex.escape(cfg.owner)}:${Regex.escape(artifact)}:)([^\"'\\s#]+)",
                RegexOption.IGNORE_CASE
            )
            val match = pattern.find(text)
                ?: return ConfigState.NOT_MANAGED to "installed as a plain jar"

            if (match.groupValues[2] == tag) return ConfigState.UPDATED to "application.yml already points to $tag"

            val updated = pattern.replace(text) { it.groupValues[1] + tag }

            Files.copy(file, file.resolveSibling(file.fileName.toString() + ".tunnel.bak"), StandardCopyOption.REPLACE_EXISTING)

            val tmp = file.resolveSibling(file.fileName.toString() + ".tunnel.tmp")
            Files.writeString(tmp, updated)
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: Exception) {
                // e.g. a single file mounted into Docker can't be replaced, write it in place
                Files.writeString(file, updated)
                Files.deleteIfExists(tmp)
            }
            return ConfigState.UPDATED to "application.yml now points to $tag"
        } catch (e: Exception) {
            return ConfigState.FAILED to (e.message ?: e.javaClass.simpleName)
        }
    }

    private fun looksLikeTunnel(jar: Path): Boolean = runCatching {
        ZipFile(jar.toFile()).use { zip -> zip.getEntry("com/pawan/tunnel/TunnelSourceManager.class") != null }
    }.getOrDefault(false)

    // ---------------------------------------------------------------- deleting old versions

    private fun same(a: Path, b: Path): Boolean =
        a.toAbsolutePath().normalize() == b.toAbsolutePath().normalize()

    private fun runningJar(): Path? = runCatching {
        val p = Paths.get(TunnelUpdater::class.java.protectionDomain.codeSource.location.toURI())
        if (Files.isRegularFile(p) && p.toString().endsWith(".jar")) p else null
    }.getOrNull()

    /** Deletes (or, with deleteOldVersions=false, renames to *.bak) every Tunnel jar except [keep]. */
    private fun removeOldJars(dir: Path, keep: Path, artifact: String, delete: Boolean) {
        val old = mutableListOf<Path>()

        Files.list(dir).use { stream ->
            stream.forEach {
                val n = it.fileName.toString()
                if (n.startsWith("$artifact-") && n.endsWith(".jar") && !same(it, keep)) old.add(it)
                if (delete && n.startsWith("$artifact-") && n.endsWith(".jar.bak")) deleteQuietly(it)
            }
        }

        // the jar that is running right now, if it has a different name
        runningJar()?.let { running -> if (!same(running, keep) && old.none { same(it, running) }) old.add(running) }

        for (jar in old) {
            try {
                if (delete) {
                    Files.deleteIfExists(jar)
                    log.info("[Tunnel] deleted old version {}", jar.fileName)
                } else {
                    Files.move(jar, jar.resolveSibling(jar.fileName.toString() + ".bak"), StandardCopyOption.REPLACE_EXISTING)
                }
            } catch (e: Exception) {
                // e.g. Windows keeps the running jar locked: remove it when Lavalink exits
                if (delete) jar.toFile().deleteOnExit()
                log.warn("[Tunnel] could not remove old jar {} now ({}), it is removed when Lavalink stops.", jar.fileName, e.message)
            }
        }
    }

    /** On startup: remove older Tunnel jars that are not the running one, and leftover *.part / *.bak files. */
    private fun cleanupStale(cfg: AutoUpdateConfig) {
        val dir = Paths.get(cfg.pluginsDir)
        if (!Files.isDirectory(dir)) return

        val artifact = artifactName(cfg)
        val running = runningJar()
        val versioned = Regex("^v?\\d+(\\.\\d+)*$")

        Files.list(dir).use { stream ->
            stream.forEach { f ->
                val n = f.fileName.toString()
                if (!n.startsWith("$artifact-")) return@forEach

                if (n.endsWith(".jar.part") || n.endsWith(".jar.bak")) {
                    deleteQuietly(f)
                } else if (n.endsWith(".jar")) {
                    val version = n.removePrefix("$artifact-").removeSuffix(".jar")
                    val isRunning = running != null && same(f, running)
                    if (!isRunning && versioned.matches(version) && isNewer(currentVersion, version.removePrefix("v"))) {
                        deleteQuietly(f)
                        log.info("[Tunnel] removed old version {}", n)
                    }
                }
            }
        }
    }

    private fun deleteQuietly(p: Path) {
        try {
            Files.deleteIfExists(p)
        } catch (e: Exception) {
            log.warn("[Tunnel] could not delete {}: {}", p.fileName, e.message)
        }
    }

    /** 1.10.0 > 1.9.3, missing parts count as 0. */
    private fun isNewer(candidate: String, current: String): Boolean {
        fun parts(v: String) = v.split('.', '-', '+').map { it.toIntOrNull() ?: 0 }
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
