package com.peco2282.obfaryn.standard

import com.peco2282.obfaryn.api.PlatformOptions
import com.peco2282.obfaryn.api.assembly.AssemblyContext
import com.peco2282.obfaryn.api.assembly.OutputProcessor
import com.peco2282.obfaryn.api.assembly.TransformationPackage
import com.peco2282.obfaryn.api.assembly.TransformationPackageProvider
import com.peco2282.obfaryn.api.transformer.TransformerRegistry
import java.nio.file.Path

/**
 * Test-only recipe selected by id `test-recorder`. It registers no transformer; it only records that
 * [AssemblyContext.outputProcessor]'s `afterWrite` is invoked by the assembly, and optionally calls
 * [AssemblyContext.protectInput] so a test can assert the overwrite guard fires. It verifies that a
 * provider-supplied package reaches a cross-artifact assembly (`--package`/`--no-default-package`
 * equivalents) and that output-processor wiring matches single-artifact runs.
 */
object RecordingPackageState {
  val afterWrites = mutableListOf<Path>()

  /** When non-null, the package calls `protectInput` with this path during configure. */
  var protectInputPath: Path? = null

  fun reset() {
    afterWrites.clear()
    protectInputPath = null
  }
}

class RecordingPackage : TransformationPackage {
  override fun configure(context: AssemblyContext, registry: TransformerRegistry) {
    RecordingPackageState.protectInputPath?.let(context::protectInput)
    context.outputProcessor(object : OutputProcessor {
      override fun afterWrite(output: Path) { RecordingPackageState.afterWrites.add(output) }
    })
  }
}

class RecordingPackageProvider : TransformationPackageProvider {
  override val id = "test-recorder"
  override fun create(options: PlatformOptions): TransformationPackage = RecordingPackage()
}
