package com.xf8410.umamobile

import org.json.JSONObject
import java.io.File

object RemoteIndexVerifier {
    data class RemoteRecord(
        val fileId: Long,
        val relativePath: String,
        val byteLength: Long,
        val sha256: String?,
    )

    fun compare(manifestFile: File, remote: List<RemoteRecord>): List<String> {
        val manifest = JSONObject(manifestFile.readText(Charsets.UTF_8))
        val records = manifest.getJSONArray("files")
        val errors = ArrayList<String>()
        val localById = HashMap<Long, JSONObject>()
        for (index in 0 until records.length()) {
            val item = records.getJSONObject(index)
            val id = item.getLong("file_id")
            if (localById.put(id, item) != null) errors += "manifest_duplicate_file_id:$id"
        }
        val remoteById = HashMap<Long, RemoteRecord>()
        remote.forEach { item ->
            if (remoteById.put(item.fileId, item) != null) errors += "remote_duplicate_file_id:${item.fileId}"
        }
        (localById.keys - remoteById.keys).sorted().forEach { errors += "remote_file_missing:$it" }
        (remoteById.keys - localById.keys).sorted().forEach { errors += "remote_file_added:$it" }
        (localById.keys intersect remoteById.keys).sorted().forEach { id ->
            val local = requireNotNull(localById[id])
            val source = requireNotNull(remoteById[id])
            val localPath = local.getString("relative_path")
            val localLength = local.getLong("byte_length")
            if (localPath != source.relativePath) errors += "remote_path_changed:$id"
            if (localLength != source.byteLength) errors += "remote_length_changed:$id:local=$localLength:remote=${source.byteLength}"
            if (source.sha256 != null) {
                val localIndexedSha = local.optString("indexed_sha256", "")
                val localPayloadSha = local.optString("local_sha256", "")
                if (!source.sha256.equals(localIndexedSha, true)) errors += "remote_indexed_sha_changed:$id"
                if (!source.sha256.equals(localPayloadSha, true)) errors += "remote_payload_sha_mismatch:$id"
            }
        }
        val expectedCount = manifest.getInt("file_count")
        val expectedBytes = manifest.getLong("total_bytes")
        val remoteBytes = remote.fold(0L) { total, item -> Math.addExact(total, item.byteLength) }
        if (expectedCount != remote.size) errors += "remote_file_count_changed:local=$expectedCount:remote=${remote.size}"
        if (expectedBytes != remoteBytes) errors += "remote_byte_count_changed:local=$expectedBytes:remote=$remoteBytes"
        return errors
    }
}
