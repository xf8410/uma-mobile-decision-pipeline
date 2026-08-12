package com.xf8410.umamobile

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class RemoteIndexVerifierTest {
    @Test
    fun identicalRemoteIndexPasses() {
        val manifest = manifest()
        val errors = RemoteIndexVerifier.compare(manifest, listOf(
            RemoteIndexVerifier.RemoteRecord(1, "payload.bin", 3, "abc")
        ))
        assertTrue(errors.joinToString(), errors.isEmpty())
    }

    @Test
    fun changedRemoteLengthAndNewFileFail() {
        val manifest = manifest()
        val errors = RemoteIndexVerifier.compare(manifest, listOf(
            RemoteIndexVerifier.RemoteRecord(1, "payload.bin", 4, "abc"),
            RemoteIndexVerifier.RemoteRecord(2, "new.bin", 1, null),
        ))
        assertTrue(errors.any { it.startsWith("remote_length_changed:1") })
        assertTrue(errors.any { it == "remote_file_added:2" })
        assertTrue(errors.any { it.startsWith("remote_file_count_changed:") })
        assertTrue(errors.any { it.startsWith("remote_byte_count_changed:") })
    }

    private fun manifest() = Files.createTempFile("uma-manifest", ".json").toFile().apply {
        writeText(JSONObject().apply {
            put("file_count", 1)
            put("total_bytes", 3)
            put("files", JSONArray().put(JSONObject().apply {
                put("file_id", 1)
                put("relative_path", "payload.bin")
                put("byte_length", 3)
                put("indexed_sha256", "abc")
                put("local_sha256", "abc")
            }))
        }.toString())
        deleteOnExit()
    }
}
