package com.xf8410.umamobile

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class SessionIntegrityVerifierTest {
    @Test
    fun completeSessionPassesIncludingZeroByteFile() {
        val root = Files.createTempDirectory("uma-session-test").toFile()
        try {
            writeSession(root, listOf("protocol/request/1/payload.bin" to byteArrayOf(1, 2, 3), "empty.bin" to byteArrayOf()))
            val report = SessionIntegrityVerifier.verify(root)
            assertTrue(report.errors.joinToString(), report.complete)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun changedPayloadFailsHashVerification() {
        val root = Files.createTempDirectory("uma-session-test").toFile()
        try {
            writeSession(root, listOf("payload.bin" to byteArrayOf(1, 2, 3)))
            File(root, "raw/payload.bin").writeBytes(byteArrayOf(1, 2, 4))
            val report = SessionIntegrityVerifier.verify(root)
            assertFalse(report.complete)
            assertTrue(report.errors.any { it.startsWith("sha256_mismatch:") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun unfinishedPartFailsVerification() {
        val root = Files.createTempDirectory("uma-session-test").toFile()
        try {
            writeSession(root, listOf("payload.bin" to byteArrayOf(1)))
            File(root, "raw/payload.bin.part").writeBytes(byteArrayOf(1))
            val report = SessionIntegrityVerifier.verify(root)
            assertFalse(report.complete)
            assertTrue(report.errors.any { it.startsWith("unfinished_part:") })
        } finally {
            root.deleteRecursively()
        }
    }

    private fun writeSession(root: File, files: List<Pair<String, ByteArray>>) {
        val records = JSONArray()
        var total = 0L
        files.forEachIndexed { index, (path, bytes) ->
            val target = File(root, "raw/$path")
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
            total += bytes.size
            records.put(JSONObject().apply {
                put("file_id", index + 1L)
                put("relative_path", path)
                put("byte_length", bytes.size)
                put("local_sha256", sha256(bytes))
            })
        }
        val manifest = JSONObject().apply {
            put("session_id", root.name)
            put("file_count", files.size)
            put("total_bytes", total)
            put("files", records)
        }
        File(root, "manifest.json").writeText(manifest.toString(), Charsets.UTF_8)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
