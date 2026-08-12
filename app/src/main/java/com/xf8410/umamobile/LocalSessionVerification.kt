package com.xf8410.umamobile

import java.io.File

object LocalSessionVerification {
    fun verifyLatest(sessionsRoot: File): Result {
        val candidates = sessionsRoot.listFiles()
            ?.filter { it.isDirectory && File(it, "manifest.json").isFile }
            .orEmpty()
        if (candidates.isEmpty()) {
            return Result(null, null, "没有已同步的本地 Session")
        }
        val latest = candidates.maxWithOrNull(
            compareBy<File> { File(it, "manifest.json").lastModified() }.thenBy { it.name }
        ) ?: return Result(null, null, "没有已同步的本地 Session")
        return Result(latest, SessionIntegrityVerifier.verify(latest), null)
    }

    data class Result(
        val sessionRoot: File?,
        val report: SessionIntegrityVerifier.Report?,
        val error: String?,
    ) {
        fun render(): String {
            error?.let { return "完整性验证失败\n$it" }
            val value = requireNotNull(report)
            return buildString {
                appendLine(if (value.complete) "完整性验证通过" else "完整性验证失败")
                appendLine("session_id=${value.sessionId}")
                appendLine("files=${value.verifiedFiles}/${value.expectedFiles}")
                appendLine("bytes=${value.verifiedBytes}/${value.expectedBytes}")
                appendLine("path=${sessionRoot?.absolutePath}")
                if (value.errors.isNotEmpty()) {
                    appendLine("errors=${value.errors.size}")
                    value.errors.forEach { appendLine(it) }
                }
            }.trimEnd()
        }
    }
}
