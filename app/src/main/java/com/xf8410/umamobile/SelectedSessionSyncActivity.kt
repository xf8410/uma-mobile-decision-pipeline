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
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.Executors

class SelectedSessionSyncActivity : androidx.activity.ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { text = "准备下载已选择的 Session"; textSize = 14f; typeface = Typeface.MONOSPACE; setTextIsSelectable(true); setPadding(24, 24, 24, 48) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(24, 32, 24, 24)
            addView(Button(context).apply { text = "下载已选择的 Session 原始文件"; setOnClickListener { start() } }, width())
            addView(status, width())
        }
        setContentView(ScrollView(this).apply { addView(content) })
    }
    private fun start() {
        status.text = "正在连接 SO 并下载已选择的 Session……"
        executor.execute { val result = runCatching { sync() }.fold({ it }, { e -> "下载失败\n${e.javaClass.simpleName}: ${e.message.orEmpty()}" }); runOnUiThread { status.text = result } }
    }
    private fun sync(): String {
        val id = getSharedPreferences(SessionSelectionActivity.PREFERENCES, MODE_PRIVATE).getString(SessionSelectionActivity.KEY_SELECTED_SESSION, null)?.takeIf { it.isNotBlank() } ?: throw IllegalStateException("尚未选择历史 Session，请先返回首页选择")
        val sessions = json("/storage/sessions"); val selected = (0 until sessions.optJSONArray("sessions")!!.length()).map { sessions.getJSONArray("sessions").getJSONObject(it) }.firstOrNull { it.optString("session_id") == id } ?: throw IllegalStateException("已选择的 Session 不存在：$id")
        val records = files(id); val root = File(filesDir, "sessions/$id"); val raw = File(root, "raw").apply { mkdirs() }; var downloaded = 0; var resumed = 0; var reused = 0; var bytes = 0L; val out = JSONArray()
        records.forEach { r -> val mode = fetch(raw, r); when (mode.first) { "downloaded" -> downloaded++; "resumed" -> resumed++; "verified_existing" -> reused++ }; bytes += r.length; out.put(JSONObject().apply { put("file_id", r.id); put("relative_path", r.path); put("content_type", r.type); put("byte_length", r.length); put("indexed_sha256", r.sha ?: JSONObject.NULL); put("local_sha256", mode.second); put("created_at_ms", r.created); put("sync_mode", mode.first) }) }
        val manifest = JSONObject().apply { put("schema_version", 1); put("session_id", id); put("session_state", selected.optString("state", "unknown")); put("plugin_version", selected.optString("plugin_version", "unknown")); put("started_at_ms", selected.optLong("started_at_ms")); put("source", "http://127.0.0.1:18765"); put("file_count", records.size); put("total_bytes", bytes); put("downloaded", downloaded); put("resumed", resumed); put("verified_existing", reused); put("files", out) }
        atomic(File(root, "manifest.json"), manifest.toString(2).toByteArray())
        "下载完成\nsession_id=$id\nstate=${selected.optString("state", "unknown")}\nfiles=${records.size}\nbytes=$bytes\ndownloaded=$downloaded\nresumed=$resumed\nverified_existing=$reused"
    }
    private fun json(path: String): JSONObject { val c = open(path); return try { val code = c.responseCode; val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty(); if (code !in 200..299) throw IllegalStateException("GET $path → HTTP $code\n$body"); JSONObject(body).also { if (!it.optBoolean("ok", false)) throw IllegalStateException(body) } } finally { c.disconnect() } }
    private fun files(id: String): List<R> { val result = ArrayList<R>(); var cursor = 0L; while (true) { val root = json("/storage/files?session_id=${URLEncoder.encode(id, "UTF-8")}&cursor=$cursor&limit=1000"); val a = root.optJSONArray("files") ?: JSONArray(); for (i in 0 until a.length()) { val x = a.getJSONObject(i); result += R(x.getLong("file_id"), x.getString("relative_path"), x.getString("content_type"), x.getLong("byte_length"), x.optString("sha256").takeIf { x.has("sha256") && !x.isNull("sha256") && it.isNotEmpty() }, x.getLong("created_at_ms")) }; val next = root.getLong("next_cursor"); if (a.length() < 1000) break; if (next <= cursor) throw IllegalStateException("file cursor did not advance"); cursor = next }; return result }
    private fun fetch(root: File, r: R): Pair<String, String> { val p = java.nio.file.Paths.get(r.path); if (r.path.isBlank() || p.isAbsolute || p.normalize().startsWith("..")) throw IllegalStateException("invalid relative_path=${r.path}"); val target = File(root, r.path); if (target.isFile && target.length() == r.length) { val h = hash(target); if (r.sha == null || h.equals(r.sha, true)) return "verified_existing" to h }; target.parentFile?.mkdirs(); val part = File(target.parentFile, target.name + ".part"); if (part.length() > r.length) part.delete(); var offset = part.length(); val initial = offset; while (offset < r.length) { val n = minOf(CHUNK, r.length - offset); val c = open("/storage/read_range?file_id=${r.id}&offset=$offset&length=$n"); val b = try { if (c.responseCode != 206) throw IllegalStateException("range HTTP ${c.responseCode}"); c.inputStream.use { it.readBytes() } } finally { c.disconnect() }; if (b.isEmpty()) throw IllegalStateException("empty range for file_id=${r.id}"); FileOutputStream(part, true).use { it.write(b); it.flush(); it.fd.sync() }; offset += b.size }; if (part.length() != r.length) throw IllegalStateException("length mismatch for file_id=${r.id}"); val h = hash(part); if (r.sha != null && !h.equals(r.sha, true)) throw IllegalStateException("SHA-256 mismatch for file_id=${r.id}"); try { Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) } catch (_: Exception) { Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }; return (if (initial > 0) "resumed" else "downloaded") to h }
    private fun open(path: String) = (URL("http://127.0.0.1:18765$path").openConnection() as HttpURLConnection).apply { requestMethod = "GET"; connectTimeout = 5_000; readTimeout = 30_000; useCaches = false }
    private fun hash(f: File): String { val d = MessageDigest.getInstance("SHA-256"); f.inputStream().buffered().use { i -> val b = ByteArray(65536); while (true) { val n = i.read(b); if (n < 0) break; d.update(b, 0, n) } }; return d.digest().joinToString("") { "%02x".format(it) } }
    private fun atomic(f: File, b: ByteArray) { f.parentFile?.mkdirs(); val p = File(f.parentFile, f.name + ".part"); FileOutputStream(p).use { it.write(b); it.fd.sync() }; try { Files.move(p.toPath(), f.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) } catch (_: Exception) { Files.move(p.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING) } }
    private fun width() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    override fun onDestroy() { executor.shutdownNow(); super.onDestroy() }
    private data class R(val id: Long, val path: String, val type: String, val length: Long, val sha: String?, val created: Long)
    companion object { private const val CHUNK = 1024L * 1024L }
}
