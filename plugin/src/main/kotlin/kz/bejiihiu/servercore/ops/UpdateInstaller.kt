package kz.bejiihiu.servercore.ops

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.logging.Logger

/** result of a completed swap */
sealed interface InstallResult {
    data class Installed(val jar: File, val tag: String) : InstallResult
    data class Failed(val stage: String, val error: String) : InstallResult
}

/**
 * file-side of the auto-updater: download into a staging dir, verify the
 * sha-256 against the release's checksum asset, then swap the running jar
 * with a backup + rollback. all methods do blocking io - they are meant to
 * run on virtual threads, never on a region thread.
 *
 * the new jar always ends up under the exact file name of the currently
 * running one, so the plugins directory never holds two copies of the plugin
 * (duplicate-plugin load errors would be the alternative).
 */
class UpdateInstaller(
    /** the jar this plugin is currently running from */
    private val currentJar: File,
    pluginDataFolder: File,
    private val logger: Logger,
) {

    private val stagingDir: File = File(pluginDataFolder, STAGING_DIR_NAME)
    private val backupDir: File = File(pluginDataFolder, BACKUP_DIR_NAME)
    private val maxBackups = 3

    init {
        // pluginDataFolder = plugins/ServerCore, artifacts live under it
        stagingDir.mkdirs()
        backupDir.mkdirs()
    }

    /**
     * downloads [candidate] and swaps it in. every failure mode throws or
     * returns Failed with the stage it died in; a partially applied swap is
     * rolled back from the backup.
     */
    fun install(candidate: ReleaseCandidate, client: GithubReleaseClient): InstallResult {
        val staged = stagingDir.resolve(candidate.jarAssetName)
        try {
            // 1. download fresh bytes into staging
            client.download(candidate.jarUrl, staged.toPath())

            // 2. verify sha-256 against the release checksums; fail closed
            val expected = fetchExpectedChecksum(candidate, client)
            val actual = sha256Hex(staged)
            if (expected == null) {
                staged.delete()
                return InstallResult.Failed("checksum", "no checksum entry for ${candidate.jarAssetName} in ${GithubReleaseClient.CHECKSUM_ASSET}")
            }
            if (!MessageDigest.isEqual(expected.toByteArray(), actual.toByteArray())) {
                staged.delete()
                return InstallResult.Failed("checksum", "sha-256 mismatch: expected $expected, got $actual")
            }

            // 3. snapshot the running jar into the backup dir
            val backup = backupCurrentJar()

            try {
                // 4. atomic-ish swap onto the exact current file name
                Files.move(
                    staged.toPath(),
                    currentJar.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (swapError: Exception) {
                // atomic move unsupported on this fs - fall back to a plain move
                try {
                    Files.move(staged.toPath(), currentJar.toPath(), StandardCopyOption.REPLACE_EXISTING)
                } catch (fallbackError: Exception) {
                    rollback(backup)
                    return InstallResult.Failed("swap", "move failed: ${fallbackError.message}")
                }
                if (swapError !is java.nio.file.AtomicMoveNotSupportedException) {
                    logger.warning("[ops] swap fell back to a non-atomic move: ${swapError.message}")
                }
            }

            logger.info("[ops] update installed: ${candidate.tag} (${currentJar.name})")
            return InstallResult.Installed(currentJar, candidate.tag)
        } catch (error: Exception) {
            staged.delete()
            return InstallResult.Failed("download", "${error.javaClass.simpleName}: ${error.message}")
        }
    }

    /**
     * looks up the checksum line for the jar asset in SHA256SUMS.txt.
     * null = the asset has no entry (release built without checksums) - the
     * caller must refuse the update, we never install unverified bytes.
     */
    private fun fetchExpectedChecksum(candidate: ReleaseCandidate, client: GithubReleaseClient): String? {
        val checksumUrl = candidate.checksumUrl ?: return null
        val text = try {
            client.fetchText(checksumUrl)
        } catch (error: Exception) {
            logger.warning("[ops] cannot fetch checksums: ${error.message}")
            return null
        }
        // format: "<hex>  <filename>" (sha256sum standard two-space separator)
        for (line in text.lines()) {
            val parts = line.trim().split(Regex("""\s+"""), limit = 2)
            if (parts.size != 2) continue
            val (hash, name) = parts
            if (name.equals(candidate.jarAssetName, ignoreCase = true) &&
                hash.length == 64 &&
                hash.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
            ) {
                return hash.lowercase()
            }
        }
        return null
    }

    /** copies the running jar into .backup/, prunes old backups, returns the copy */
    private fun backupCurrentJar(): Path? {
        if (!currentJar.exists()) return null
        val target = backupDir.resolve(currentJar.name)
        Files.copy(currentJar.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        pruneOldBackups()
        return target.toPath()
    }

    /** restores the backup over the current jar; best effort, logged loudly */
    private fun rollback(backup: Path?) {
        if (backup == null || !Files.exists(backup)) {
            logger.severe("[ops] rollback impossible: no backup of ${currentJar.name}")
            return
        }
        try {
            Files.copy(backup, currentJar.toPath(), StandardCopyOption.REPLACE_EXISTING)
            logger.warning("[ops] rolled back to previous jar from $backup")
        } catch (error: Exception) {
            logger.severe("[ops] rollback failed: ${error.message}")
        }
    }

    private fun pruneOldBackups() {
        backupDir.listFiles { file -> file.isFile && file.name.endsWith(".jar") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(maxBackups)
            ?.forEach { it.delete() }
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file.toPath()).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val STAGING_DIR_NAME = ".updates"
        const val BACKUP_DIR_NAME = ".backup"
    }
}
