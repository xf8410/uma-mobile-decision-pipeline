package com.xf8410.umamobile

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Validates derived TrainingState records without modifying raw, decoded, or derived facts. */
internal class TrainingStateDerivedValidator {
    data class ValidationResult(
        val recordsChecked: Int,
        val recordsValid: Int,
        val issues: JSONArray,
    ) {
        val valid: Boolean get() = issues.length() == 0

        fun toJson(): JSONObject = JSONObject().apply {
            put("validation_schema_version", 1)
            put("valid", valid)
            put("records_checked", recordsChecked)
            put("records_valid", recordsValid)
            put("issues", issues)
        }
    }

    fun validateSession(sessionRoot: File): ValidationResult {
        val statesFile = File(sessionRoot, "derived/training-state.jsonl")
        val issues = JSONArray()
        if (!statesFile.isFile) {
            issue(issues, -1, "missing_training_state_file", statesFile.path, "derived TrainingState JSONL does not exist")
            return ValidationResult(0, 0, issues).also { writeReport(sessionRoot, it) }
        }

        var checked = 0
        var validRecords = 0
        statesFile.useLines(Charsets.UTF_8) { lines ->
            lines.forEachIndexed { lineIndex, line ->
                if (line.isBlank()) return@forEachIndexed
                checked++
                val before = issues.length()
                try {
                    val state = JSONTokener(line).nextValue() as? JSONObject
                        ?: throw IllegalArgumentException("record is not a JSON object")
                    validateRecord(sessionRoot, lineIndex + 1, state, issues)
                } catch (error: Exception) {
                    issue(issues, lineIndex + 1, "invalid_training_state_record", statesFile.path, "${error.javaClass.simpleName}: ${error.message.orEmpty()}")
                }
                if (issues.length() == before) validRecords++
            }
        }
        return ValidationResult(checked, validRecords, issues).also { writeReport(sessionRoot, it) }
    }

    private fun validateRecord(sessionRoot: File, record: Int, state: JSONObject, issues: JSONArray) {
        val decodedPath = requiredString(state, "source_file", record, issues) ?: return
        val decoded = resolveSessionPath(sessionRoot, decodedPath, record, issues) ?: return
        if (!decoded.isFile) {
            issue(issues, record, "missing_source_decoded", decodedPath, "source decoded file does not exist")
            return
        }
        verifyHash(state, "source_file_sha256", decoded, record, "decoded_sha256_mismatch", issues)

        val rawPath = nullableString(state, "source_raw")
        if (rawPath == null) {
            issue(issues, record, "missing_source_raw_reference", "source_raw", "source raw reference is null or absent")
        } else {
            val raw = resolveSessionPath(sessionRoot, rawPath, record, issues)
            if (raw != null) {
                if (!raw.isFile) issue(issues, record, "missing_source_raw", rawPath, "source raw file does not exist")
                else verifyHash(state, "source_raw_sha256", raw, record, "raw_sha256_mismatch", issues)
            }
        }

        val decodedRoot = try {
            val wrapper = JSONTokener(decoded.readText(Charsets.UTF_8)).nextValue()
            if (wrapper is JSONObject && wrapper.has("data")) wrapper else JSONObject().put("data", wrapper)
        } catch (error: Exception) {
            issue(issues, record, "invalid_source_decoded", decodedPath, "${error.javaClass.simpleName}: ${error.message.orEmpty()}")
            return
        }

        val refs = state.optJSONArray("evidence_refs")
        if (refs == null) {
            issue(issues, record, "missing_evidence_refs", "evidence_refs", "evidence reference array is absent")
            return
        }
        for (index in 0 until refs.length()) {
            val ref = refs.optJSONObject(index)
            if (ref == null) {
                issue(issues, record, "invalid_evidence_ref", "evidence_refs[$index]", "evidence reference is not an object")
                continue
            }
            validateReference(record, state, decodedRoot, ref, index, issues)
        }
    }

    private fun validateReference(record: Int, state: JSONObject, decodedRoot: JSONObject, ref: JSONObject, refIndex: Int, issues: JSONArray) {
        val target = nullableString(ref, "target")
        if (target == null) {
            issue(issues, record, "missing_evidence_target", "evidence_refs[$refIndex].target", "target is absent")
            return
        }
        val present = if (ref.has("source_present")) ref.optBoolean("source_present", false) else !ref.isNull("source_path")
        val path = nullableString(ref, "source_path")
        if (!present) {
            if (path != null) issue(issues, record, "missing_value_has_path", path, "source_present=false must have a null path")
            if (!state.has(target) || !state.isNull(target)) issue(issues, record, "missing_value_not_null", target, "missing source value must remain null")
            return
        }
        if (path == null) {
            issue(issues, record, "missing_evidence_path", "evidence_refs[$refIndex].source_path", "present source has no JSON evidence path")
            return
        }
        val sourceValue = try { resolveJsonPath(decodedRoot, path) } catch (error: Exception) {
            issue(issues, record, "unresolvable_evidence_path", path, error.message.orEmpty())
            return
        }
        if (!state.has(target)) {
            issue(issues, record, "missing_derived_target", target, "derived record does not contain evidence target")
            return
        }
        val derivedValue = state.opt(target)
        val equal = if (target == "training_data_sets") {
            derivedValue is JSONArray && jsonArrayContainsString(derivedValue, finalPathToken(path))
        } else jsonEqual(derivedValue, sourceValue)
        if (!equal) issue(issues, record, "evidence_value_mismatch", path, "derived target '$target' differs from its source value")
    }

    private fun resolveJsonPath(root: Any, path: String): Any {
        if (path.isBlank()) throw IllegalArgumentException("empty JSON path")
        var current: Any? = root
        var offset = 0
        while (offset < path.length) {
            if (path[offset] == '.') { offset++; continue }
            if (path[offset] == '[') {
                val end = path.indexOf(']', offset + 1)
                if (end < 0) throw IllegalArgumentException("unterminated array index at $offset")
                val index = path.substring(offset + 1, end).toIntOrNull() ?: throw IllegalArgumentException("invalid array index at $offset")
                val array = current as? JSONArray ?: throw IllegalArgumentException("expected array before index $index")
                if (index !in 0 until array.length()) throw IllegalArgumentException("array index $index is out of bounds")
                current = array.get(index)
                offset = end + 1
            } else {
                var end = offset
                while (end < path.length && path[end] != '.' && path[end] != '[') end++
                val key = path.substring(offset, end)
                val objectValue = current as? JSONObject ?: throw IllegalArgumentException("expected object before key '$key'")
                if (!objectValue.has(key)) throw IllegalArgumentException("missing key '$key'")
                current = objectValue.get(key)
                offset = end
            }
        }
        return current ?: JSONObject.NULL
    }

    private fun verifyHash(state: JSONObject, key: String, file: File, record: Int, code: String, issues: JSONArray) {
        val expected = nullableString(state, key)
        if (expected == null) issue(issues, record, "missing_$key", key, "required SHA-256 is null or absent")
        else {
            val actual = sha256(file)
            if (!expected.equals(actual, ignoreCase = true)) issue(issues, record, code, file.path, "expected=$expected actual=$actual")
        }
    }

    private fun resolveSessionPath(sessionRoot: File, recordedPath: String, record: Int, issues: JSONArray): File? {
        val normalized = recordedPath.replace('\\', '/')
        if (normalized.isBlank() || normalized.startsWith('/')) {
            issue(issues, record, "invalid_source_path", recordedPath, "source path must be a non-empty relative Session path")
            return null
        }
        val prefix = sessionRoot.name + "/"
        val relative = if (normalized.startsWith(prefix)) normalized.removePrefix(prefix) else normalized
        val root = sessionRoot.canonicalFile
        val resolved = File(root, relative).canonicalFile
        val rootPath = root.toPath()
        if (resolved.toPath() == rootPath || !resolved.toPath().startsWith(rootPath)) {
            issue(issues, record, "source_path_escaped_session", recordedPath, "source path resolves outside the Session root")
            return null
        }
        return resolved
    }

    private fun requiredString(value: JSONObject, key: String, record: Int, issues: JSONArray): String? =
        nullableString(value, key).also { if (it == null) issue(issues, record, "missing_$key", key, "required string is null or absent") }

    private fun nullableString(value: JSONObject, key: String): String? =
        if (!value.has(key) || value.isNull(key)) null else value.optString(key).takeIf { it.isNotBlank() }

    private fun finalPathToken(path: String): String = path.substringAfterLast('.').substringBefore('[')

    private fun jsonArrayContainsString(array: JSONArray, expected: String): Boolean {
        for (index in 0 until array.length()) if (array.optString(index) == expected) return true
        return false
    }

    private fun jsonEqual(left: Any?, right: Any?): Boolean = when {
        left === JSONObject.NULL && right === JSONObject.NULL -> true
        left is JSONObject && right is JSONObject -> left.toString() == right.toString()
        left is JSONArray && right is JSONArray -> left.toString() == right.toString()
        else -> left == right || left?.toString() == right?.toString()
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

    private fun issue(issues: JSONArray, record: Int, code: String, location: String, message: String) {
        issues.put(JSONObject().apply {
            put("record", if (record < 0) JSONObject.NULL else record)
            put("code", code)
            put("location", location)
            put("message", message)
        })
    }

    private fun writeReport(sessionRoot: File, result: ValidationResult) {
        val target = File(sessionRoot, "derived/training-state-validation.json")
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, target.name + ".part")
        FileOutputStream(part, false).use { output ->
            output.write(result.toJson().toString().toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
        try { Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: Exception) { Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    }
}
