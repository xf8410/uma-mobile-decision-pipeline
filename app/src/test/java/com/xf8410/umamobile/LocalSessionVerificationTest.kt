package com.xf8410.umamobile

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class LocalSessionVerificationTest {
    @Test
    fun reportsWhenNoLocalSessionExists() {
        val root = Files.createTempDirectory("uma-local-sessions").toFile()
        try {
            val result = LocalSessionVerification.verifyLatest(root)
            assertEquals("没有已同步的本地 Session", result.error)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun selectsNewestManifestAndRendersSuccessfulReport() {
        val root = Files.createTempDirectory("uma-local-sessions").toFile()
        try {
            val older = createSession(root, "older", byteArrayOf(1))
            val newer = createSession(root, "newer", byteArrayOf(2, 3))
            File(older, "manifest.json").setLastModified(1_000)
            File(newer, "manifest.json").setLastModified(2_000)

            val result = LocalSessionVerification.verifyLatest(root)
            assertEquals("newer", result.report?.sessionId)
            assertTrue(result.report?.complete == true)
            assertTrue(result.render().startsWith("完整性验证通过"))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun createSession(root: File, id: String, bytes: ByteArray): File {
        val session = File(root, id).apply { mkdirs() }
        val payload = File(session, "raw/payload.bin")
        payload.parentFile?.mkdirs()
        payload.writeBytes(bytes)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val records = JSONArray().put(JSONObject().apply {
            put("file_id", 1)
            put("relative_path", "payload.bin")
            put("byte_length", bytes.size)
            put("local_sha256", hash)
        })
        File(session, "manifest.json").writeText(JSONObject().apply {
            put("session_id", id)
            put("file_count", 1)
            put("total_bytes", bytes.size)
            put("files", records)
        }.toString())
        return session
    }
}
