package com.xf8410.umamobile

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

object SessionIntegrityVerifier {
    data class Report(
        val sessionId: String,
        val expectedFiles: Int,
        val verifiedFiles: Int,
        val expectedBytes: Long,
        val verifiedBytes: Long,
        val errors: List<String>,
    ) {
        val complete: Boolean
            get() = errors.isEmpty() && expectedFiles == verifiedFiles && expectedBytes == verifiedBytes
    }

    fun verify(sessionRoot: File): Report {
        val manifestFile = File(sessionRoot, "manifest.json")
        if (!manifestFile.isFile) {
            return Report(sessionRoot.name, 0, 0, 0, 0, listOf("manifest_missing"))
        }
        val manifest = try {
            JSONObject(manifestFile.readText(Charsets.UTF_8))
        } catch (error: Exception) {
            return Report(sessionRoot.name, 0, 0, 0, 0, listOf("manifest_invalid:${error.message.orEmpty()}"))
        }
        val sessionId = manifest.optString("session_id", sessionRoot.name)
        val expectedFiles = manifest.optInt("file_count", -1)
        val expectedBytes = manifest.optLong("total_bytes", -1)
        val records = manifest.optJSONArray("files")
            ?: return Report(sessionId, expectedFiles, 0, expectedBytes, 0, listOf("manifest_files_missing"))
        val rawRoot = File(sessionRoot, "raw").canonicalFile
        val errors = ArrayList<String>()
        val seenIds = HashSet<Long>()
        val seenPaths = HashSet<String>()
        var verifiedFiles = 0
        var verifiedBytes = 0L

        for (index in 0 until records.length()) {
            val record = records.optJSONObject(index)
            if (record == null) {
                errors += "record_not_object:$index"
                continue
            }
            val fileId = record.optLong("file_id", -1)
            val relativePath = record.optString("relative_path", "")
            val byteLength = record.optLong("byte_length", -1)
            val expectedSha = record.optString("local_sha256", "")
            if (fileId <= 0 || !seenIds.add(fileId)) errors += "invalid_or_duplicate_file_id:$fileId"
            if (relativePath.isEmpty() || !seenPaths.add(relativePath)) {
                errors += "invalid_or_duplicate_relative_path:$relativePath"
                continue
            }
            if (byteLength < 0) {
                errors += "negative_byte_length:$fileId"
                continue
            }
            val target = try {
                File(rawRoot, relativePath).canonicalFile
            } catch (error: Exception) {
                errors += "canonical_path_failed:$fileId:${error.message.orEmpty()}"
                continue
            }
            if (!target.toPath().startsWith(rawRoot.toPath())) {
                errors += "path_escaped_raw_root:$fileId"
                continue
            }
            if (!target.isFile) {
                errors += "file_missing:$fileId:$relativePath"
                continue
            }
            if (target.length() != byteLength) {
                errors += "length_mismatch:$fileId:expected=$byteLength:actual=${target.length()}"
                continue
            }
            val actualSha = sha256(target)
            if (expectedSha.isEmpty() || !actualSha.equals(expectedSha, ignoreCase = true)) {
                errors += "sha256_mismatch:$fileId:expected=$expectedSha:actual=$actualSha"
                continue
            }
            verifiedFiles += 1
            verifiedBytes = try {
                Math.addExact(verifiedBytes, byteLength)
            } catch (_: ArithmeticException) {
                errors += "verified_byte_total_overflow"
                verifiedBytes
            }
        }

        if (expectedFiles != records.length()) errors += "manifest_file_count_mismatch:expected=$expectedFiles:records=${records.length()}"
        if (expectedBytes < 0) errors += "invalid_manifest_total_bytes:$expectedBytes"
        if (expectedFiles != verifiedFiles) errors += "verified_file_count_mismatch:expected=$expectedFiles:actual=$verifiedFiles"
        if (expectedBytes != verifiedBytes) errors += "verified_byte_count_mismatch:expected=$expectedBytes:actual=$verifiedBytes"
        rawRoot.walkTopDown().filter { it.isFile && it.name.endsWith(".part") }.forEach {
            errors += "unfinished_part:${it.relativeTo(rawRoot).path}"
        }
        return Report(sessionId, expectedFiles, verifiedFiles, expectedBytes, verifiedBytes, errors)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
