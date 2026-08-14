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
    private val derivationCore = TrainingStateDerivationCore()
    private val orderingModel = SessionRecordOrderingModel()
    private val derivedValidator = TrainingStateDerivedValidator()
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
        text = "从已解码文件生成并验证 TrainingState 证据"
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
        status.text = "正在生成并验证版本化 TrainingState 证据，raw 和 decoded 文件保持不变……"
        executor.execute {
            val result = runCatching { derive(sessionId) }.fold(
                { it },
                { error -> "TrainingState 生成失败\n${error.javaClass.simpleName}: ${error.message.orEmpty()}" },
            )
            runOnUiThread { status.text = result }
        }
    }

    private fun derive(sessionId: String): String {
        derivationCore.verifySyntheticFixture()
        orderingModel.verifySyntheticFixture()
        val root = File(filesDir, "sessions/$sessionId")
        val filesRoot = File(root, "decoded/files")
        if (!filesRoot.isDirectory) throw IllegalStateException("缺少 decoded/files/，请先执行解码")
        val metadataByRawPath = readOrderingMetadata(root)
        val candidates = ArrayList<SortableTrainingState>()
        val errors = JSONArray()
        var inspected = 0
        var candidatesMatched = 0
        filesRoot.walkTopDown().filter { it.isFile && it.extension == "json" }.forEach { file ->
            inspected++
            val relative = file.relativeTo(File(filesDir, "sessions")).path
            try {
                val wrapper = JSONTokener(file.readText(Charsets.UTF_8)).nextValue()
                val decodedData = if (wrapper is JSONObject && wrapper.has("data")) wrapper.opt("data") else wrapper
                val located = derivationCore.locateTrainingData(decodedData) ?: return@forEach
                candidatesMatched++
                val meta = (wrapper as? JSONObject)?.optJSONObject("_meta")
                val sourceRaw = metaValue(meta, "source_raw")
                val sourceRawSha256 = metaValue(meta, "sha256")
                val state = derivationCore.deriveState(
                    sessionId = sessionId,
                    candidateIndex = candidatesMatched,
                    sourceFile = relative,
                    sourceDecodedSha256 = sha256(file),
                    sourceRaw = sourceRaw,
                    sourceRawSha256 = sourceRawSha256,
                    located = located,
                )
                val rawPath = (sourceRaw as? String)?.replace('\\', '/')
                candidates += SortableTrainingState(
                    originalTraversalIndex = candidatesMatched,
                    state = state,
                    metadata = rawPath?.let { metadataByRawPath[it] },
                )
            } catch (error: Exception) {
                errors.put(JSONObject().apply {
                    put("schema_version", derivationCore.schemaVersion)
                    put("session_id", sessionId)
                    put("source_file", relative)
                    put("source_file_sha256", runCatching { sha256(file) }.getOrNull() ?: JSONObject.NULL)
                    put("mapping_status", MappingStatus.UNKNOWN.serialized)
                    put("error", "${error.javaClass.simpleName}: ${error.message.orEmpty()}")
                })
            }
        }
        val ordered = orderingModel.order(candidates)
        val output = JSONArray().apply { ordered.records.forEach { put(it.state) } }
        val statesEmitted = output.length()
        val derived = File(root, "derived")
        atomicWrite(File(derived, "training-state.jsonl"), lines(output))
        atomicWrite(File(derived, "training-state-errors.json"), errors.toString().toByteArray(Charsets.UTF_8))
        atomicWrite(File(derived, "field-mapping.v${derivationCore.mappingVersion}.json"), derivationCore.fieldMappingDocument().toString().toByteArray(Charsets.UTF_8))
        atomicWrite(File(derived, "training-state-manifest.json"), JSONObject().apply {
            put("schema_version", derivationCore.schemaVersion)
            put("mapping_version", derivationCore.mappingVersion)
            put("session_id", sessionId)
            put("source", "decoded/files")
            put("raw_preserved", true)
            put("decoded_preserved", true)
            put("files_inspected", inspected)
            put("candidates_matched", candidatesMatched)
            put("states_emitted", statesEmitted)
            put("errors", errors.length())
            put("selection_gate", "one object directly contains chara_info and at least one *_data_set")
            put("candidate_index_policy", "1-based order of matching source candidates before derivation; gaps in emitted records indicate retained derivation errors")
            put("ordering_basis", ordered.basis.serialized)
            put("ordering_policy", ordered.policy)
            put("field_policy", "awaiting_real_session_validation semantic mappings; missing values remain null; every emitted value links to decoded and raw evidence")
            put("synthetic_validation", "passed; test-only and not evidence of game semantics")
        }.toString().toByteArray(Charsets.UTF_8))

        val validation = derivedValidator.validateSession(root)
        val validationStatus = if (validation.valid) "通过" else "失败（派生文件已保留）"
        return "TrainingState 证据生成与验证完成\nsession_id=$sessionId\nfiles_inspected=$inspected\ncandidates_matched=$candidatesMatched\nstates_emitted=$statesEmitted\nderivation_errors=${errors.length()}\nordering_basis=${ordered.basis.serialized}\nmapping_version=${derivationCore.mappingVersion}\nvalidation=$validationStatus\nrecords_checked=${validation.recordsChecked}\nrecords_valid=${validation.recordsValid}\nvalidation_issues=${validation.issues.length()}\nderived/training-state.jsonl\nderived/field-mapping.v${derivationCore.mappingVersion}.json\nderived/training-state-manifest.json\nderived/training-state-errors.json\nderived/training-state-validation.json"
    }

    private fun readOrderingMetadata(sessionRoot: File): Map<String, ConfirmedOrderingMetadata> {
        val manifestFile = File(sessionRoot, "manifest.json")
        if (!manifestFile.isFile) return emptyMap()
        val manifest = JSONTokener(manifestFile.readText(Charsets.UTF_8)).nextValue() as? JSONObject ?: return emptyMap()
        val files = manifest.optJSONArray("files") ?: return emptyMap()
        val result = LinkedHashMap<String, ConfirmedOrderingMetadata>()
        for (index in 0 until files.length()) {
            val record = files.optJSONObject(index) ?: continue
            if (!record.has("relative_path") || record.isNull("relative_path") || !record.has("created_at_ms") || record.isNull("created_at_ms")) continue
            val relativePath = record.optString("relative_path").replace('\\', '/').trimStart('/')
            val createdAt = runCatching { record.getLong("created_at_ms") }.getOrNull() ?: continue
            result["raw/$relativePath"] = ConfirmedOrderingMetadata(
                observedAtMs = createdAt,
                evidenceFile = "manifest.json",
                evidencePath = "files[$index].created_at_ms",
            )
        }
        return result
    }

    private fun metaValue(meta: JSONObject?, key: String): Any =
        if (meta != null && meta.has(key) && !meta.isNull(key)) meta.opt(key) ?: JSONObject.NULL else JSONObject.NULL

    private fun lines(array: JSONArray): ByteArray = buildString {
        for (index in 0 until array.length()) append(array.getJSONObject(index).toString()).append('\n')
    }.toByteArray(Charsets.UTF_8)

    private fun selectedSession(): String? = getSharedPreferences(SessionSelectionActivity.PREFERENCES, MODE_PRIVATE)
        .getString(SessionSelectionActivity.KEY_SELECTED_SESSION, null)

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
        content.addView(Button(this).apply {
            isAllCaps = false
            text = file.relativeTo(File(filesDir, "sessions")).path
            setOnClickListener { showFile(file) }
        }, layoutParams())
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
        content.addView(TextView(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 12f; setTextIsSelectable(true); text = displayed; setPadding(16, 16, 16, 48)
        }, layoutParams())
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
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, target.name + ".part")
        FileOutputStream(part, false).use { output -> output.write(bytes); output.flush(); output.fd.sync() }
        try { Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: Exception) { Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    }

    override fun onDestroy() { executor.shutdownNow(); super.onDestroy() }

    private data class Results(val summary: String, val files: List<File>)
}

internal enum class MappingStatus(val serialized: String) {
    CONFIRMED("confirmed"),
    CANDIDATE("candidate"),
    UNKNOWN("unknown"),
    AWAITING_REAL_SESSION_VALIDATION("awaiting_real_session_validation"),
}

internal enum class OrderingBasis(val serialized: String) {
    SOURCE_CREATED_AT_MS("source_created_at_ms"),
    SOURCE_CREATED_AT_MS_WITH_UNKNOWN_TIES("source_created_at_ms_with_unknown_ties"),
    UNKNOWN_ORIGINAL_TRAVERSAL("unknown_original_traversal"),
}

internal data class ConfirmedOrderingMetadata(
    val observedAtMs: Long,
    val evidenceFile: String,
    val evidencePath: String,
)

internal data class SortableTrainingState(
    val originalTraversalIndex: Int,
    val state: JSONObject,
    val metadata: ConfirmedOrderingMetadata?,
)

internal data class OrderedTrainingStates(
    val records: List<SortableTrainingState>,
    val basis: OrderingBasis,
    val policy: String,
)

/** Uses only explicit Session index timestamps; filenames are never interpreted as protocol order. */
internal class SessionRecordOrderingModel {
    fun order(input: List<SortableTrainingState>): OrderedTrainingStates {
        val allHaveTime = input.isNotEmpty() && input.all { it.metadata != null }
        val ordered: List<SortableTrainingState>
        val basis: OrderingBasis
        val policy: String
        if (!allHaveTime) {
            ordered = input.sortedBy { it.originalTraversalIndex }
            basis = OrderingBasis.UNKNOWN_ORIGINAL_TRAVERSAL
            policy = "at least one record lacks confirmed ordering metadata; preserve original decoded-file traversal; do not infer order from filenames"
        } else {
            ordered = input.sortedWith(compareBy<SortableTrainingState> { it.metadata!!.observedAtMs }.thenBy { it.originalTraversalIndex })
            val duplicates = ordered.groupBy { it.metadata!!.observedAtMs }.values.any { it.size > 1 }
            basis = if (duplicates) OrderingBasis.SOURCE_CREATED_AT_MS_WITH_UNKNOWN_TIES else OrderingBasis.SOURCE_CREATED_AT_MS
            policy = if (duplicates) {
                "sort by manifest files[].created_at_ms; equal timestamps preserve original traversal and remain an unknown tie, without filename inference"
            } else {
                "sort by manifest files[].created_at_ms; do not infer order from filenames"
            }
        }
        ordered.forEachIndexed { index, record -> annotate(record, index + 1, basis) }
        return OrderedTrainingStates(ordered, basis, policy)
    }

    private fun annotate(record: SortableTrainingState, order: Int, basis: OrderingBasis) {
        record.state.put("record_order", order)
        record.state.put("ordering_basis", basis.serialized)
        record.state.put("ordering_status", if (basis == OrderingBasis.UNKNOWN_ORIGINAL_TRAVERSAL) MappingStatus.UNKNOWN.serialized else MappingStatus.CONFIRMED.serialized)
        val metadata = record.metadata
        record.state.put("ordering_evidence", if (metadata == null || basis == OrderingBasis.UNKNOWN_ORIGINAL_TRAVERSAL) JSONObject.NULL else JSONObject().apply {
            put("source_file", metadata.evidenceFile)
            put("source_path", metadata.evidencePath)
            put("observed_at_ms", metadata.observedAtMs)
        })
        record.state.put("ordering_tie_basis", if (basis == OrderingBasis.SOURCE_CREATED_AT_MS_WITH_UNKNOWN_TIES) OrderingBasis.UNKNOWN_ORIGINAL_TRAVERSAL.serialized else JSONObject.NULL)
    }

    /** Synthetic/test-only checks prove ordering behavior, not actual game protocol order. */
    fun verifySyntheticFixture() {
        fun record(id: String, traversal: Int, time: Long?) = SortableTrainingState(
            traversal,
            JSONObject().put("fixture_id", id),
            time?.let { ConfirmedOrderingMetadata(it, "synthetic/test-only-manifest.json", "files[$traversal].created_at_ms") },
        )
        val sorted = order(listOf(record("late", 1, 20), record("tie-a", 2, 10), record("tie-b", 3, 10)))
        check(sorted.records.map { it.state.getString("fixture_id") } == listOf("tie-a", "tie-b", "late"))
        check(sorted.basis == OrderingBasis.SOURCE_CREATED_AT_MS_WITH_UNKNOWN_TIES)
        val unknown = order(listOf(record("first", 1, 20), record("second", 2, null)))
        check(unknown.records.map { it.state.getString("fixture_id") } == listOf("first", "second"))
        check(unknown.basis == OrderingBasis.UNKNOWN_ORIGINAL_TRAVERSAL)
        check(unknown.records.all { it.state.isNull("ordering_evidence") })
    }
}

internal data class LocatedTrainingData(val value: JSONObject, val evidencePath: String)

internal data class TrainingFieldMapping(
    val target: String,
    val sourceKeys: List<String>,
    val status: MappingStatus = MappingStatus.AWAITING_REAL_SESSION_VALIDATION,
)

/** Android-independent and deterministically replayable TrainingState derivation rules. */
internal class TrainingStateDerivationCore {
    val schemaVersion: Int = 3
    val mappingVersion: Int = 2

    private val fieldMappings = listOf(
        TrainingFieldMapping("turn", listOf("turn")),
        TrainingFieldMapping("playing_state", listOf("playing_state")),
        TrainingFieldMapping("speed", listOf("speed")),
        TrainingFieldMapping("stamina", listOf("stamina")),
        TrainingFieldMapping("power", listOf("power")),
        TrainingFieldMapping("guts", listOf("guts")),
        TrainingFieldMapping("wisdom", listOf("wiz", "wisdom")),
        TrainingFieldMapping("max_speed", listOf("max_speed")),
        TrainingFieldMapping("max_stamina", listOf("max_stamina")),
        TrainingFieldMapping("max_power", listOf("max_power")),
        TrainingFieldMapping("max_guts", listOf("max_guts")),
        TrainingFieldMapping("max_wisdom", listOf("max_wiz", "max_wisdom")),
        TrainingFieldMapping("vital", listOf("vital")),
        TrainingFieldMapping("max_vital", listOf("max_vital")),
    )

    fun locateTrainingData(value: Any?, evidencePath: String = "data"): LocatedTrainingData? {
        when (value) {
            is JSONObject -> {
                val keys = objectKeys(value)
                if (value.opt("chara_info") is JSONObject && keys.any { it.endsWith("_data_set") }) return LocatedTrainingData(value, evidencePath)
                keys.forEach { key -> locateTrainingData(value.opt(key), "$evidencePath.$key")?.let { return it } }
            }
            is JSONArray -> for (index in 0 until value.length()) locateTrainingData(value.opt(index), "$evidencePath[$index]")?.let { return it }
        }
        return null
    }

    fun deriveState(sessionId: String, candidateIndex: Int, sourceFile: String, sourceDecodedSha256: String, sourceRaw: Any, sourceRawSha256: Any, located: LocatedTrainingData): JSONObject {
        val chara = located.value.optJSONObject("chara_info") ?: throw IllegalArgumentException("located object no longer contains chara_info")
        val dataSetKeys = objectKeys(located.value).filter { it.endsWith("_data_set") }
        if (dataSetKeys.isEmpty()) throw IllegalArgumentException("located object no longer contains *_data_set")
        return JSONObject().apply {
            put("schema_version", schemaVersion); put("mapping_version", mappingVersion); put("session_id", sessionId)
            put("candidate_index", candidateIndex); put("source_file", sourceFile); put("source_file_sha256", sourceDecodedSha256)
            put("source_raw", sourceRaw); put("source_raw_sha256", sourceRawSha256)
            fieldMappings.forEach { mapping -> put(mapping.target, mappedValue(chara, mapping)) }
            put("training_data_sets", stringArray(dataSetKeys))
            put("event_data", if (located.value.has("unchecked_event_array")) located.value.opt("unchecked_event_array") else JSONObject.NULL)
            put("mapping_status", MappingStatus.AWAITING_REAL_SESSION_VALIDATION.serialized)
            put("evidence_refs", evidenceRefs(sourceFile, sourceDecodedSha256, sourceRaw, sourceRawSha256, located, chara, dataSetKeys))
        }
    }

    fun fieldMappingDocument(): JSONObject = JSONObject().apply {
        put("schema_version", 1); put("mapping_version", mappingVersion)
        put("allowed_mapping_statuses", stringArray(MappingStatus.values().map { it.serialized }))
        put("status_policy", "field-name semantic mappings remain awaiting_real_session_validation until supported by real replay evidence")
        put("mappings", JSONArray().apply { fieldMappings.forEach { mapping -> put(JSONObject().apply {
            put("source_paths", stringArray(mapping.sourceKeys.map { "data.chara_info.$it" })); put("target", mapping.target)
            put("mapping_status", mapping.status.serialized); put("value_policy", "first present non-null source key; otherwise null")
        }) } })
    }

    /** Repeatable synthetic/test-only invariant check; it does not confirm any game field semantics. */
    fun verifySyntheticFixture() {
        val duplicateEvents = JSONArray().put("same").put("same").put(JSONObject.NULL)
        val source = JSONObject().apply {
            put("unknown_fixture_field", JSONObject().put("kept", true))
            put("chara_info", JSONObject().apply { put("speed", 0); put("vital", JSONObject.NULL) })
            put("synthetic_data_set", JSONArray().put(2).put(1).put(1)); put("unchecked_event_array", duplicateEvents)
        }
        val located = locateTrainingData(source) ?: error("synthetic fixture was not located")
        val state = deriveState("synthetic-test-only", 1, "decoded/synthetic.json", "decoded-sha", "raw/synthetic", "raw-sha", located)
        check(state.getInt("speed") == 0); check(state.isNull("vital")); check(state.getJSONArray("event_data").toString() == duplicateEvents.toString())
        check(located.value.getJSONObject("unknown_fixture_field").getBoolean("kept")); check(state.getJSONArray("training_data_sets").getString(0) == "synthetic_data_set")
        check(state.getString("mapping_status") == MappingStatus.AWAITING_REAL_SESSION_VALIDATION.serialized)
        check(state.getInt("candidate_index") == 1)
    }

    private fun evidenceRefs(decodedFile: String, decodedSha256: String, sourceRaw: Any, sourceRawSha256: Any, located: LocatedTrainingData, chara: JSONObject, dataSetKeys: List<String>): JSONArray = JSONArray().apply {
        fieldMappings.forEach { mapping ->
            val key = mapping.sourceKeys.firstOrNull { chara.has(it) && !chara.isNull(it) }
            put(JSONObject().apply {
                put("target", mapping.target); put("mapping_version", mappingVersion); put("mapping_status", mapping.status.serialized)
                put("source_decoded", decodedFile); put("source_decoded_sha256", decodedSha256); put("source_raw", sourceRaw); put("source_raw_sha256", sourceRawSha256)
                put("source_path", if (key == null) JSONObject.NULL else "${located.evidencePath}.chara_info.$key"); put("source_present", key != null)
            })
        }
        dataSetKeys.forEach { key -> put(JSONObject().apply {
            put("target", "training_data_sets"); put("mapping_status", MappingStatus.CONFIRMED.serialized); put("source_decoded", decodedFile)
            put("source_decoded_sha256", decodedSha256); put("source_raw", sourceRaw); put("source_raw_sha256", sourceRawSha256); put("source_path", "${located.evidencePath}.$key")
        }) }
        if (located.value.has("unchecked_event_array")) put(JSONObject().apply {
            put("target", "event_data"); put("mapping_status", MappingStatus.CONFIRMED.serialized); put("source_decoded", decodedFile)
            put("source_decoded_sha256", decodedSha256); put("source_raw", sourceRaw); put("source_raw_sha256", sourceRawSha256)
            put("source_path", "${located.evidencePath}.unchecked_event_array")
        })
    }

    private fun mappedValue(chara: JSONObject, mapping: TrainingFieldMapping): Any {
        mapping.sourceKeys.forEach { key -> if (chara.has(key) && !chara.isNull(key)) return chara.opt(key) ?: JSONObject.NULL }
        return JSONObject.NULL
    }

    private fun objectKeys(value: JSONObject): List<String> {
        val result = ArrayList<String>(); val keys = value.keys(); while (keys.hasNext()) result += keys.next(); return result
    }

    private fun stringArray(values: List<String>): JSONArray = JSONArray().apply { values.forEach { put(it) } }
}
