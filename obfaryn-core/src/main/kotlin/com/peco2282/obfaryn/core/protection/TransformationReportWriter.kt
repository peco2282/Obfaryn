package com.peco2282.obfaryn.core.protection

import com.peco2282.obfaryn.api.transformer.TransformerReport
import java.nio.file.*

/** Streaming, atomically replaced JSON; no serializer dependency in processed applications. */
object TransformationReportWriter {
  private fun quote(text: String): String = buildString {
    append('"')
    for (c in text) when (c) {
      '"' -> append("\\\"")
      '\\' -> append("\\\\")
      else -> if (c.code < 32) append("\\u" + c.code.toString(16).padStart(4, '0')) else append(c)
    }
    append('"')
  }

  fun write(path: Path, reports: List<TransformerReport>) {
    val target = path.toAbsolutePath().normalize()
    Files.createDirectories(target.parent)
    val temporary = Files.createTempFile(target.parent, ".obfaryn-report-", ".json")
    try {
      Files.newBufferedWriter(temporary, Charsets.UTF_8).use { writer ->
        writer.write("{\"schemaVersion\":1,\"passes\":[\n")
        reports.forEachIndexed { index, report ->
          if (index > 0) writer.write(",\n")
          writer.write("{\"id\":${quote(report.id)},\"metrics\":")
          writer.write(report.metrics.entries.joinToString(",", "{", "}") { (k, v) -> "${quote(k)}:$v" })
          writer.write(",\"methods\":[")
          report.methods.forEachIndexed { row, m ->
            if (row > 0) writer.write(",")
            writer.write("{\"method\":${quote(m.method)},\"status\":${quote(m.status)},\"reason\":${quote(m.reason)}," +
              "\"inputInstructions\":${m.inputInstructions},\"outputInstructions\":${m.outputInstructions}," +
              "\"inputBranches\":${m.inputBranches},\"outputBranches\":${m.outputBranches}," +
              "\"inputCodeBytes\":${m.inputCodeBytes},\"outputCodeBytes\":${m.outputCodeBytes}}")
          }
          writer.write("]}")
        }
        writer.write("\n]}\n")
      }
      try {
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
      } catch (_: AtomicMoveNotSupportedException) {
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
      }
    } finally { Files.deleteIfExists(temporary) }
  }
}
