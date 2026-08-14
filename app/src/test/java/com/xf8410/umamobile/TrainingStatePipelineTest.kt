package com.xf8410.umamobile

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.nio.file.Files

class TrainingStatePipelineTest {
    @Test
    fun derivationPreservesZeroNullDuplicatesAndEvidence() {
        val core = TrainingStateDerivationCore()
        val events = JSONArray().put("same").put("same").put(JSONObject.NULL)
        val data = JSONObject().apply {
            put("chara_info", JSONObject().apply {
                put("turn", 12)
                put("speed", 0)
                put("wiz", 321)
                put("vital", JSONObject.NULL)
            })
            put("home_info_data_set", JSONArray().put(2).put(1).put(1))
            put("unchecked_event_array", events)
        }

        val located = core.locateTrainingData(data)!!
        val state = core.deriveState("session", 7, "decoded/source.json", "decoded-hash", "raw/source", "raw-hash", located)

        assertEquals(7, state.getInt("candidate_index"))
        assertEquals(0, state.getInt("speed"))
        assertEquals(321, state.getInt("wisdom"))
        assertTrue(state.isNull("vital"))
        assertEquals(events.toString(), state.getJSONArray("event_data").toString())
        assertEquals("home_info_data_set", state.getJSONArray("training_data_sets").getString(0))
        assertTrue(state.getJSONArray("evidence_refs").length() > 0)
    }

    @Test
    fun orderingUsesConfirmedTimeAndPreservesUnknownTies() {
        fun record(id: String, traversal: Int, time: Long?) = SortableTrainingState(
            traversal,
            JSONObject().put("id", id),
            time?.let { ConfirmedOrderingMetadata(it, "manifest.json", "files[$traversal].created_at_ms") },
        )
        val result = SessionRecordOrderingModel().order(listOf(
            record("late", 1, 20),
            record("tie-a", 2, 10),
            record("tie-b", 3, 10),
        ))

        assertEquals(listOf("tie-a", "tie-b", "late"), result.records.map { it.state.getString("id") })
        assertEquals(OrderingBasis.SOURCE_CREATED_AT_MS_WITH_UNKNOWN_TIES, result.basis)
        assertEquals(listOf(1, 2, 3), result.records.map { it.state.getInt("record_order") })
    }

    @Test
    fun validatorAcceptsLinkedEvidenceAndRejectsEscapedSourcePath() {
        val sessionRoot = Files.createTempDirectory("training-state-validator").toFile()
        try {
            val raw = File(sessionRoot, "raw/source.bin").apply { parentFile!!.mkdirs(); writeText("raw") }
            val decoded = File(sessionRoot, "decoded/files/source.json").apply {
                parentFile!!.mkdirs()
                writeText(JSONObject().apply {
                    put("_meta", JSONObject().put("source_raw", "raw/source.bin").put("sha256", sha256(raw)))
                    put("data", JSONObject().apply {
                        put("chara_info", JSONObject().put("speed", 123))
                        put("home_info_data_set", JSONArray().put(1))
                    })
                }.toString())
            }
            val core = TrainingStateDerivationCore()
            val wrapper = JSONObject(decoded.readText())
            val located = core.locateTrainingData(wrapper.get("data"))!!
            val state = core.deriveState(
                sessionId = sessionRoot.name,
                candidateIndex = 1,
                sourceFile = "decoded/files/source.json",
                sourceDecodedSha256 = sha256(decoded),
                sourceRaw = "raw/source.bin",
                sourceRawSha256 = sha256(raw),
                located = located,
            )
            File(sessionRoot, "derived/training-state.jsonl").apply {
                parentFile!!.mkdirs()
                writeText(state.toString() + "\n")
            }

            val validator = TrainingStateDerivedValidator()
            val valid = validator.validateSession(sessionRoot)
            assertTrue(valid.toJson().toString(), valid.valid)
            assertEquals(1, valid.recordsChecked)
            assertEquals(1, valid.recordsValid)
            assertTrue(File(sessionRoot, "derived/training-state-validation.json").isFile)

            state.put("source_file", "../outside.json")
            File(sessionRoot, "derived/training-state.jsonl").writeText(state.toString() + "\n")
            val escaped = validator.validateSession(sessionRoot)
            assertFalse(escaped.valid)
            assertTrue(issueCodes(escaped.issues).contains("source_path_escaped_session"))
        } finally {
            sessionRoot.deleteRecursively()
        }
    }

    private fun issueCodes(issues: JSONArray): List<String> =
        (0 until issues.length()).map { issues.getJSONObject(it).getString("code") }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(file.readBytes())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
