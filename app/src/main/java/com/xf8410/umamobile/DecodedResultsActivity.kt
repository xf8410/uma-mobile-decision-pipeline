package com.xf8410.umamobile

import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors

class DecodedResultsActivity : ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var content: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 32, 24, 24) }
        status = TextView(this).apply { textSize = 14f; typeface = Typeface.MONOSPACE; setTextIsSelectable(true); setPadding(16, 16, 16, 24) }
        content.addView(Button(this).apply {
            text = "从已解码文件生成 TrainingState 证据"
            setOnClickListener { deriveTrainingState() }
        }, layoutParams())
        content.addView(status, layoutParams())
        setContentView(ScrollView(this).apply { addView(content) })
        load()
    }

    private fun load() {
        val sessionId = getSharedPreferences(SessionSelectionActivity.PREFERENCES, MODE_PRIVATE).getString(SessionSelectionActivity.KEY_SELECTED_SESSION, null)
        if (sessionId.isNullOrBlank()) { status.text = "尚未选择历史 Session"; return }
        status.text = "正在读取解码结果……"
        executor.execute {
            var summary = ""
            var files = emptyList<File>()
            try { val data = readResults(sessionId); summary = data.summary; files = data.files }
            catch (error: Exception) { summary = "读取失败\n${error.javaClass.simpleName}: ${error.message.orEmpty()}" }
            val resultSummary = summary; val resultFiles = files
            runOnUiThread {
                content.removeAllViews()
                content.addView(Button(this).apply { text = "从已解码文件生成 TrainingState 证据"; setOnClickListener { deriveTrainingState() } }, layoutParams())
                content.addView(status, layoutParams()); status.text = resultSummary
                resultFiles.forEach { file -> addFileButton(file) }
            }
        }
    }

    private fun deriveTrainingState() {
        val sessionId = getSharedPreferences(SessionSelectionActivity.PREFERENCES, MODE_PRIVATE).getString(SessionSelectionActivity.KEY_SELECTED_SESSION, null)
        if (sessionId.isNullOrBlank()) { status.text = "尚未选择历史 Session"; return }
        status.text = "正在生成 TrainingState 证据，原始和 decoded 文件不会修改……"
        executor.execute {
            val result = runCatching { derive(sessionId) }.fold(
                { it },
                { error -> "TrainingState 生成失败\n${error.javaClass.simpleName}: ${error.message.orEmpty()}" },
            )
            runOnUiThread { status.text = result }
        }
    }

    private fun derive(sessionId: String): String {
        val root = File(filesDir, "sessions/$sessionId")
        val filesRoot = File(root, "decoded/files")
        if (!filesRoot.isDirectory) throw IllegalStateException("缺少 decoded/files/，请先执行解码")
        val output = JSONArray()
        val errors = JSONArray()
        var inspected = 0
        var matched = 0
        filesRoot.walkTopDown().filter { it.isFile && it.extension == "json" }.forEach { file ->
            inspected++
            val relative = file.relativeTo(File(filesDir, "sessions")).path
            try {
                val wrapper = JSONTokener(file.readText(Charsets.UTF_8)).nextValue()
                val data = if (wrapper is JSONObject && wrapper.has("data")) wrapper.opt("data") else wrapper
                val rootObject = data as? JSONObject ?: return@forEach
                val dataSetKeys = jsonObjectKeys(rootObject).filter { it.endsWith("_data_set") }
                val chara = findObject(rootObject, "chara_info") ?: return@forEach
                if (dataSetKeys.isEmpty()) return@forEach
                matched++
                val state = JSONObject().apply {
                    put("schema_version", 1)
                    put("session_id", sessionId)
                    put("sequence", matched)
                    put("source_file", relative)
                    put("source_raw", sourceRawFromWrapper(wrapper, relative))
                    put("turn", valueOrNull(chara, "turn"))
                    put("playing_state", valueOrNull(chara, "playing_state"))
                    put("speed", valueOrNull(chara, "speed"))
                    put("stamina", valueOrNull(chara, "stamina"))
                    put("power", valueOrNull(chara, "power"))
                    put("guts", valueOrNull(chara, "guts"))
                    put("wisdom", valueOrNull(chara, "wiz", "wisdom"))
                    put("max_speed", valueOrNull(chara, "max_speed"))
                    put("max_stamina", valueOrNull(chara, "max_stamina"))
                    put("max_power", valueOrNull(chara, "max_power"))
                    put("max_guts", valueOrNull(chara, "max_guts"))
                    put("max_wisdom", valueOrNull(chara, "max_wiz", "max_wisdom"))
                    put("vital", valueOrNull(chara, "vital"))
                    put("max_vital", valueOrNull(chara, "max_vital"))
                    put("training_data_sets", jsonArrayOfStrings(dataSetKeys))
                    put("event_data", valueOrNull(rootObject, "unchecked_event_array"))
                    put("mapping_status", "confirmed-source-candidate-values")
                    put("evidence_refs", evidenceRefs(relative, chara, dataSetKeys))
                }
                output.put(state)
            } catch (error: Exception) {
                errors.put(JSONObject().apply { put("source_file", relative); put("error", "${error.javaClass.simpleName}: ${error.message.orEmpty()}") })
            }
        }
        val derived = File(root, "derived")
        atomicWrite(File(derived, "training-state.jsonl"), lines(output))
        atomicWrite(File(derived, "training-state-errors.json"), errors.toString())
        atomicWrite(File(derived, "training-state-manifest.json"), JSONObject().apply {
            put("schema_version", 1); put("session_id", sessionId); put("source", "decoded/files"); put("raw_preserved", true)
            put("decoded_preserved", true); put("files_inspected", inspected); put("states_emitted", matched); put("errors", errors.length())
            put("selection_gate", "same data object contains chara_info and at least one *_data_set")
            put("field_policy", "source-linked values only; missing values remain null; no raw fields are removed")
        }.toString())
        return "TrainingState 证据生成完成\nsession_id=$sessionId\nfiles_inspected=$inspected\nstates_emitted=$matched\nerrors=${errors.length()}\nderived/training-state.jsonl\nderived/training-state-manifest.json\nderived/training-state-errors.json"
    }

    private fun evidenceRefs(relative: String, chara: JSONObject, dataSetKeys: List<String>): JSONArray {
        val refs = JSONArray()
        refs.put(JSONObject().apply { put("source_file", relative); put("source_path", "data.chara_info"); put("fields_present", jsonArrayOfStrings(jsonObjectKeys(chara))) })
        dataSetKeys.forEach { key -> refs.put(JSONObject().apply { put("source_file", relative); put("source_path", "data.$key") }) }
        return refs
    }

    private fun jsonObjectKeys(value: JSONObject): List<String> {
        val result = ArrayList<String>()
        val keys = value.keys()
        while (keys.hasNext()) result += keys.next()
        return result
    }

    private fun jsonArrayOfStrings(values: List<String>): JSONArray {
        val result = JSONArray()
        values.forEach { result.put(it) }
        return result
    }

    private fun sourceRawFromWrapper(wrapper: Any?, relative: String): String =
        if (wrapper is JSONObject) wrapper.optJSONObject("_meta")?.optString("source_raw")?.takeIf { it.isNotBlank() } ?: "raw/${relative.substringAfter("/decoded/files/")}" else "unknown"

    private fun findObject(value: Any?, key: String): JSONObject? {
        when (value) {
            is JSONObject -> {
                if (value.opt(key) is JSONObject) return value.optJSONObject(key)
                val keys = value.keys()
                while (keys.hasNext()) findObject(value.opt(keys.next()), key)?.let { return it }
            }
            is JSONArray -> for (index in 0 until value.length()) findObject(value.opt(index), key)?.let { return it }
        }
        return null
    }

    private fun valueOrNull(objectValue: JSONObject, vararg keys: String): Any {
        for (key in keys) {
            if (objectValue.has(key) && !objectValue.isNull(key)) return objectValue.opt(key)
        }
        return JSONObject.NULL
    }

    private fun lines(array: JSONArray): ByteArray = buildString { for (index in 0 until array.length()) append(array.getJSONObject(index).toString()).append('\n') }.toByteArray(Charsets.UTF_8)

    private fun readResults(sessionId: String): Results {
        val decoded = File(filesDir, "sessions/$sessionId/decoded")
        if (!decoded.isDirectory) throw IllegalStateException("缺少 decoded/，请先执行解码")
        val filesRoot = File(decoded, "files")
        val files = if (filesRoot.isDirectory) filesRoot.walkTopDown().filter { it.isFile && it.extension == "json" }.toList() else emptyList()
        val errorsFile = File(decoded, "decode-errors.jsonl")
        val errorCount = if (errorsFile.isFile) errorsFile.readLines().count { it.isNotBlank() } else 0
        return Results("当前 Session：$sessionId\n解码成功：${files.size}\n解码失败：$errorCount", files)
    }

    private fun addFileButton(file: File) {
        content.addView(Button(this).apply { isAllCaps = false; text = file.relativeTo(File(filesDir, "sessions")).path; setOnClickListener { showFile(file) } }, layoutParams())
    }

    private fun showFile(file: File) {
        val displayed = try {
            val value = JSONTokener(file.readText(Charsets.UTF_8)).nextValue()
            when (value) {
                is JSONObject -> value.toString(2)
                is JSONArray -> value.toString(2)
                else -> JSONObject().put("value", value).toString(2)
            }
        } catch (_: Exception) { file.readText(Charsets.UTF_8) }
        content.removeAllViews()
        content.addView(Button(this).apply { text = "返回解码文件列表"; setOnClickListener { load() } }, layoutParams())
        content.addView(TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 12f; setTextIsSelectable(true); text = displayed; setPadding(16, 16, 16, 48) }, layoutParams())
    }

    private fun layoutParams() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun atomicWrite(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs(); val part = File(target.parentFile, target.name + ".part")
        FileOutputStream(part, false).use { output -> output.write(bytes); output.flush(); output.fd.sync() }
        try { Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: Exception) { Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    }
    override fun onDestroy() { executor.shutdownNow(); super.onDestroy() }
    private data class Results(val summary: String, val files: List<File>)
}
