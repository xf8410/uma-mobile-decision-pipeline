package com.xf8410.umamobile

import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.Executors

class LocalSnapshotVerificationActivity : androidx.activity.ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply {
            text = "选择已下载的 Session 后开始本地验收"
            textSize = 14f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(24, 24, 24, 48)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 32, 24, 24)
            addView(Button(context).apply {
                text = "重新校验本地快照并重建索引"
                setOnClickListener { verifySelected() }
            }, matchWidth())
            addView(status, matchWidth())
        }
        setContentView(ScrollView(this).apply { addView(content) })
    }

    private fun verifySelected() {
        status.text = "正在读取手机本地 raw 并重新计算 SHA-256……"
        executor.execute {
            val result = runCatching { verify() }.fold(
                { it },
                { error -> "本地验收失败\n${error.javaClass.simpleName}: ${error.message.orEmpty()}" },
            )
            runOnUiThread { status.text = result }
        }
    }

    private fun verify(): String {
        val preferences = getSharedPreferences(SessionSelectionActivity.PREFERENCES, MODE_PRIVATE)
        val sessionId = preferences.getString(SessionSelectionActivity.KEY_SELECTED_SESSION, null)
            ?.takeIf { it.isNotBlank() } ?: throw IllegalStateException("尚未选择历史 Session")
        val root = File(filesDir, "sessions/$sessionId")
        val manifestFile = File(root, "manifest.json")
        val rawRoot = File(root, "raw")
        if (!manifestFile.isFile) throw IllegalStateException("缺少 manifest.json")
        if (!rawRoot.isDirectory) throw IllegalStateException("缺少 raw/")
        val manifest = JSONObject(manifestFile.readText(Charsets.UTF_8))
        val expected = manifest.optJSONArray("files") ?: throw IllegalStateException("manifest 缺少 files")
        val entries = JSONArray()
        val missing = mutableListOf<String>()
        val sizeMismatch = mutableListOf<String>()
        val hashMismatch = mutableListOf<String>()
        val seen = linkedSetOf<String>()
        var verifiedFiles = 0
        var verifiedBytes = 0L
        for (index in 0 until expected.length()) {
            val item = expected.getJSONObject(index)
            val relative = item.getString("relative_path")
            validateRelativePath(relative)
            if (!seen.add(relative)) throw IllegalStateException("manifest 存在重复路径：$relative")
            val file = File(rawRoot, relative)
            val expectedLength = item.getLong("byte_length")
            val indexedHash = item.optString("indexed_sha256").takeIf { item.has("indexed_sha256") && !item.isNull("indexed_sha256") && it.isNotBlank() }
            if (!file.isFile) { missing += relative; continue }
            if (file.length() != expectedLength) { sizeMismatch += relative; continue }
            val actualHash = sha256(file)
            if (indexedHash != null && !actualHash.equals(indexedHash, ignoreCase = true)) { hashMismatch += relative; continue }
            verifiedFiles++
            verifiedBytes = Math.addExact(verifiedBytes, file.length())
            entries.put(JSONObject().apply {
                put("relative_path", relative)
                put("byte_length", file.length())
                put("sha256", actualHash)
                put("content_type", item.optString("content_type", "application/octet-stream"))
                put("file_id", item.optLong("file_id"))
                put("created_at_ms", item.optLong("created_at_ms"))
            })
        }
        val partial = mutableListOf<String>()
        val extra = mutableListOf<String>()
        rawRoot.walkTopDown().filter { it.isFile }.forEach { file ->
            val relative = rawRoot.toPath().relativize(file.toPath()).toString()
            if (relative.endsWith(".part")) partial += relative else if (!seen.contains(relative)) extra += relative
        }
        val complete = missing.isEmpty() && sizeMismatch.isEmpty() && hashMismatch.isEmpty() && partial.isEmpty() && verifiedFiles == expected.length()
        val state = manifest.optString("session_state", "unknown")
        val verificationStatus = if (complete && state == "open") "snapshot_verified" else if (complete) "verified" else "incomplete"
        val verification = JSONObject().apply {
            put("schema_version", 1); put("session_id", sessionId); put("session_state", state)
            put("status", verificationStatus); put("verified_file_count", verifiedFiles)
            put("verified_bytes", verifiedBytes); put("expected_file_count", expected.length())
            put("missing_files", JSONArray(missing)); put("size_mismatch_files", JSONArray(sizeMismatch))
            put("sha256_mismatch_files", JSONArray(hashMismatch)); put("partial_files", JSONArray(partial))
            put("extra_files", JSONArray(extra)); put("verified_at_ms", System.currentTimeMillis())
        }
        atomicWrite(File(root, "verification.json"), verification.toString(2).toByteArray(Charsets.UTF_8))
        atomicWrite(File(root, "local-index.json"), JSONObject().apply {
            put("schema_version", 1); put("session_id", sessionId); put("session_state", state)
            put("status", verificationStatus); put("files", entries)
        }.toString(2).toByteArray(Charsets.UTF_8))
        return "本地快照验收完成\nsession_id=$sessionId\nstatus=$verificationStatus\nverified_files=$verifiedFiles/${expected.length()}\nverified_bytes=$verifiedBytes\nverification.json 与 local-index.json 已更新"
    }

    private fun validateRelativePath(relative: String) {
        val path = java.nio.file.Paths.get(relative)
        if (relative.isBlank() || path.isAbsolute || path.normalize().startsWith("..")) throw IllegalStateException("非法相对路径：$relative")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, target.name + ".part")
        FileOutputStream(part, false).use { output -> output.write(bytes); output.flush(); output.fd.sync() }
        try { Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: Exception) { Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    }

    private fun matchWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    override fun onDestroy() { executor.shutdownNow(); super.onDestroy() }
}
