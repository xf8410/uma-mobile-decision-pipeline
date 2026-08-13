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
import java.security.MessageDigest
import java.util.concurrent.Executors

class DecodedResultsActivity : ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var content: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 32, 24, 24) }
        status = TextView(this).apply { textSize = 14f; typeface = Typeface.MONOSPACE; setTextIsSelectable(true); setPadding(16, 16, 16, 24) }
        content.addView(deriveButton(), layoutParams())
        content.addView(status, layoutParams())
        setContentView(ScrollView(this).apply { addView(content) })
        load()
    }

    private fun deriveButton() = Button(this).apply {
        text = "从已解码文件生成版本化 TrainingState 证据"
        setOnClickListener { deriveTrainingState() }
    }

    private fun load() {
        val sessionId = selectedSession()
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
                content.addView(deriveButton(), layoutParams())
                content.addView(status, layoutParams()); status.text = resultSummary
                resultFiles.forEach { file -> addFileButton(file) }
            }
        }
    }

    private fun deriveTrainingState() {
        val sessionId = selectedSession()
        if (sessionId.isNullOrBlank()) { status.text = "尚未选择历史 Session"; return }
        status.text = "正在生成版本化 TrainingState 证据，raw 和 decoded 文件保持不变……"
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
                val decodedData = if (wrapper is JSONObject && wrapper.has("data")) wrapper.opt("data") else wrapper
                val located = locateTrainingData(decodedData) ?: return@forEach
                val chara = located.value.optJSONObject("chara_info") ?: return@forEach
                val dataSetKeys = jsonObjectKeys(located.value).filter { it.endsWith("_data_set") }
                val meta = (wrapper as? JSONObject)?.optJSONObject("_meta")
                val sourceRaw = metaValue(meta, "source_raw")
                val sourceRawSha256 = metaValue(meta, "sha256")
                matched++
                val state = JSONObject().apply {
                    put("schema_version", TRAINING_STATE_SCHEMA_VERSION)
                    put("mapping_version", FIELD_MAPPING_VERSION)
                    put("session_id", sessionId)
                    put("derivation_index", matched)
                    put("source_file", relative)
                    put("source_file_sha256", sha256(file))
                    put("source_raw", sourceRaw)
                    put("source_raw_sha256", sourceRawSha256)
                    FIELD_MAPPINGS.forEach { mapping -> put(mapping.target, mappedValue(chara, mapping)) }
                    put("training_data_sets", jsonArrayOfStrings(dataSetKeys))
                    put("event_data", if (located.value.has("unchecked_event_array")) located.value.opt("unchecked_event_array") else JSONObject.NULL)
                    put("mapping_status", "versioned-candidate-semantics")
                    put("evidence_refs", evidenceRefs(relative, sourceRaw, sourceRawSha256, located, chara, dataSetKeys))
                }
                output.put(state)
            } catch (error: Exception) {
                errors.put(JSONObject().apply {
                    put("source_file", relative)
                    put("source_file_sha256", runCatching { sha256(file) }.getOrNull() ?: JSONObject.NULL)
                    put("error", "${error.javaClass.simpleName}: ${error.message.orEmpty()}")
                })
            }
        }
        val derived = File(root, "derived")
        atomicWrite(File(derived, "training-state.jsonl"), lines(output))
        atomicWrite(File(derived, "training-state-errors.json"), errors.toString().toByteArray(Charsets.UTF_8))
        atomicWrite(File(derived, "field-mapping.v$FIELD_MAPPING_VERSION.json"), fieldMappingDocument().toString().toByteArray(Charsets.UTF_8))
        atomicWrite(File(derived, "training-state-manifest.json"), JSONObject().apply {
            put("schema_version", TRAINING_STATE_SCHEMA_VERSION)
            put("mapping_version", FIELD_MAPPING_VERSION)
            put("session_id", sessionId)
            put("source", "decoded/files")
            put("raw_preserved", true)
            put("decoded_preserved", true)
            put("files_inspected", inspected)
            put("states_emitted", matched)
            put("errors", errors.length())
            put("selection_gate", "one object directly contains chara_info and at least one *_data_set")
            put("ordering_policy", "derivation_index records traversal only and is not asserted as protocol sequence")
            put("field_policy", "candidate semantic mappings only; missing values remain null; every emitted value links to decoded and raw evidence")
        }.toString().toByteArray(Charsets.UTF_8))
        return "TrainingState 证据生成完成\nsession_id=$sessionId\nfiles_inspected=$inspected\nstates_emitted=$matched\nerrors=${errors.length()}\nmapping_version=$FIELD_MAPPING_VERSION\nderived/training-state.jsonl\nderived/field-mapping.v$FIELD_MAPPING_VERSION.json\nderived/training-state-manifest.json\nderived/training-state-errors.json"
    }

    private fun fieldMappingDocument(): JSONObject = JSONObject().apply {
        put("schema_version", 1)
        put("mapping_version", FIELD_MAPPING_VERSION)
        put("confidence_policy", "candidate means source field is observed but player semantics still require replay evidence")
        put("mappings", JSONArray().apply {
            FIELD_MAPPINGS.forEach { mapping -> put(JSONObject().apply {
                put("source_paths", jsonArrayOfStrings(mapping.sourceKeys.map { "data.chara_info.$it" }))
                put("target", mapping.target)
                put("confidence", "candidate")
                put("value_policy", "first present non-null source key; otherwise null")
            }) }
        })
    }

    private fun evidenceRefs(
        decodedFile: String,
        sourceRaw: Any,
        sourceRawSha256: Any,
        located: LocatedObject,
        chara: JSONObject,
        dataSetKeys: List<String>,
    ): JSONArray = JSONArray().apply {
        FIELD_MAPPINGS.forEach { mapping ->
            val key = mapping.sourceKeys.firstOrNull { chara.has(it) && !chara.isNull(it) }
            put(JSONObject().apply {
                put("target", mapping.target)
                put("mapping_version", FIELD_MAPPING_VERSION)
                put("confidence", "candidate")
                put("source_decoded", decodedFile)
                put("source_raw", sourceRaw)
                put("source_raw_sha256", sourceRawSha256)
                put("source_path", if (key == null) JSONObject.NULL else "${located.path}.chara_info.$key")
                put("source_present", key != null)
            })
        }
        dataSetKeys.forEach { key -> put(JSONObject().apply {
            put("target", "training_data_sets")
            put("confidence", "confirmed-structure")
            put("source_decoded", decodedFile)
            put("source_raw", sourceRaw)
            put("source_raw_sha256", sourceRawSha256)
            put("source_path", "${located.path}.$key")
        }) }
        if (located.value.has("unchecked_event_array")) put(JSONObject().apply {
            put("target", "event_data")
            put("confidence", "confirmed-structure")
            put("source_decoded", decodedFile)
            put("source_raw", sourceRaw)
            put("source_raw_sha256", sourceRawSha256)
            put("source_path", "${located.path}.unchecked_event_array")
        })
    }

    private fun locateTrainingData(value: Any?, path: String = "data"): LocatedObject? {
        when (value) {
            is JSONObject -> {
                val keys = jsonObjectKeys(value)
                if (value.opt("chara_info") is JSONObject && keys.any { it.endsWith("_data_set") }) return LocatedObject(value, path)
                keys.forEach { key -> locateTrainingData(value.opt(key), "$path.$key")?.let { return it } }
            }
            is JSONArray -> for (index in 0 until value.length()) locateTrainingData(value.opt(index), "$path[$index]")?.let { return it }
        }
        return null
    }

    private fun mappedValue(chara: JSONObject, mapping: FieldMapping): Any {
        mapping.sourceKeys.forEach { key -> if (chara.has(key) && !chara.isNull(key)) return chara.opt(key) ?: JSONObject.NULL }
        return JSONObject.NULL
    }

    private fun metaValue(meta: JSONObject?, key: String): Any =
        if (meta != null && meta.has(key) && !meta.isNull(key)) meta.opt(key) ?: JSONObject.NULL else JSONObject.NULL

    private fun jsonObjectKeys(value: JSONObject): List<String> {
        val result = ArrayList<String>()
        val keys = value.keys()
        while (keys.hasNext()) result += keys.next()
        return result
    }

    private fun jsonArrayOfStrings(values: List<String>): JSONArray = JSONArray().apply { values.forEach { put(it) } }
    private fun lines(array: JSONArray): ByteArray = buildString { for (index in 0 until array.length()) append(array.getJSONObject(index).toString()).append('\n') }.toByteArray(Charsets.UTF_8)
    private fun selectedSession(): String? = getSharedPreferences(SessionSelectionActivity.PREFERENCES, MODE_PRIVATE).getString(SessionSelectionActivity.KEY_SELECTED_SESSION, null)

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

    private fun layoutParams() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun atomicWrite(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs(); val part = File(target.parentFile, target.name + ".part")
        FileOutputStream(part, false).use { output -> output.write(bytes); output.flush(); output.fd.sync() }
        try { Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: Exception) { Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    }

    override fun onDestroy() { executor.shutdownNow(); super.onDestroy() }

    private data class Results(val summary: String, val files: List<File>)
    private data class LocatedObject(val value: JSONObject, val path: String)
    private data class FieldMapping(val target: String, val sourceKeys: List<String>)

    companion object {
        private const val TRAINING_STATE_SCHEMA_VERSION = 2
        private const val FIELD_MAPPING_VERSION = 1
        private val FIELD_MAPPINGS = listOf(
            FieldMapping("turn", listOf("turn")),
            FieldMapping("playing_state", listOf("playing_state")),
            FieldMapping("speed", listOf("speed")),
            FieldMapping("stamina", listOf("stamina")),
            FieldMapping("power", listOf("power")),
            FieldMapping("guts", listOf("guts")),
            FieldMapping("wisdom", listOf("wiz", "wisdom")),
            FieldMapping("max_speed", listOf("max_speed")),
            FieldMapping("max_stamina", listOf("max_stamina")),
            FieldMapping("max_power", listOf("max_power")),
            FieldMapping("max_guts", listOf("max_guts")),
            FieldMapping("max_wisdom", listOf("max_wiz", "max_wisdom")),
            FieldMapping("vital", listOf("vital")),
            FieldMapping("max_vital", listOf("max_vital")),
        )
    }
}
