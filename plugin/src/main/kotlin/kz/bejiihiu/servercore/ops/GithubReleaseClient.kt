package kz.bejiihiu.servercore.ops

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.logging.Logger

@Serializable
private data class GithubAsset(
    val name: String,
    val browserDownloadUrl: String,
)

@Serializable
private data class GithubRelease(
    val tagName: String,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GithubAsset> = emptyList(),
)

/** one release worth updating to, with everything the installer needs */
sealed interface ReleaseLookup {
    data class Found(val candidate: ReleaseCandidate) : ReleaseLookup
    /** no build-* release in the listing */
    data object None : ReleaseLookup
    /** http/api failure - caller logs the reason and waits for the next cycle */
    data class Error(val reason: String) : ReleaseLookup
}

/**
 * github releases api client over java.net.http, built for virtual threads:
 * every call blocks, so callers must run it on a virtual thread (the
 * scheduling glue in UpdaterService guarantees that).
 */
class GithubReleaseClient(
    private val repo: String,
    private val tokenEnv: String,
    private val logger: Logger,
) {

    private val json = Json { ignoreUnknownKeys = true }

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /** latest release tagged build-<n>-<sha> from the configured repo */
    fun latestBuildRelease(): ReleaseLookup {
        val releases = try {
            fetchJson("https://api.github.com/repos/$repo/releases?per_page=30")
        } catch (error: Exception) {
            return ReleaseLookup.Error("${error.javaClass.simpleName}: ${error.message}")
        }

        val parsed = try {
            json.decodeFromString<List<GithubRelease>>(releases)
        } catch (error: Exception) {
            return ReleaseLookup.Error("bad releases payload: ${error.message}")
        }

        // releases come newest first; first build-* match wins. drafts are
        // invisible to anonymous callers anyway, prereleases we skip too.
        val release = parsed.firstOrNull { !it.draft && !it.prerelease && BuildVersion.parse(it.tagName) != null }
            ?: return ReleaseLookup.None

        val jar = release.assets.firstOrNull { it.name.endsWith(".jar") }
        if (jar == null) {
            logger.warning("[ops] release ${release.tagName} has no jar asset, skipping")
            return ReleaseLookup.None
        }
        val checksums = release.assets.firstOrNull { it.name.equals(CHECKSUM_ASSET, ignoreCase = true) }

        return ReleaseLookup.Found(
            ReleaseCandidate(
                tag = release.tagName,
                version = BuildVersion.parse(release.tagName),
                jarUrl = jar.browserDownloadUrl,
                jarAssetName = jar.name,
                checksumUrl = checksums?.browserDownloadUrl,
                checksumAssetName = checksums?.name,
            ),
        )
    }

    /** streams the release asset into [target]; returns the byte count written */
    fun download(url: String, target: java.nio.file.Path): Long {
        val request = baseRequest(url).GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofFile(target))
        if (response.statusCode() !in 200..299) {
            target.toFile().delete()
            error("download failed: http ${response.statusCode()} for $url")
        }
        return response.body().toFile().length()
    }

    /** fetches a small text asset (checksums file) */
    fun fetchText(url: String): String {
        val request = baseRequest(url).GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            error("fetch failed: http ${response.statusCode()} for $url")
        }
        return response.body()
    }

    private fun fetchJson(url: String): String {
        val request = baseRequest(url)
            .GET()
            .setHeader("Accept", "application/vnd.github+json")
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            error("github api returned http ${response.statusCode()} for $url")
        }
        return response.body()
    }

    private fun baseRequest(url: String): HttpRequest.Builder {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .setHeader("X-GitHub-Api-Version", "2022-11-28")
        // optional token just to lift the 60 req/h anonymous rate limit
        val token = tokenEnv.takeIf { it.isNotBlank() }?.let { System.getenv(it) }
        if (!token.isNullOrBlank()) {
            builder.setHeader("Authorization", "Bearer $token")
        }
        return builder
    }

    companion object {
        /** checksums asset the release workflow publishes next to the artifacts */
        const val CHECKSUM_ASSET = "SHA256SUMS.txt"
    }
}
