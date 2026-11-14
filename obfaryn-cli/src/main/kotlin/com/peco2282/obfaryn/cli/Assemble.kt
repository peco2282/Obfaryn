package com.peco2282.obfaryn.cli

import com.peco2282.obfaryn.core.ObfuscationConfig
import com.peco2282.obfaryn.core.withExtensionClassLoader
import com.peco2282.obfaryn.standard.AssemblyModule
import com.peco2282.obfaryn.standard.AssemblySession
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Option
import picocli.CommandLine.Spec
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.Callable

/**
 * Obfuscates several JARs in one cross-artifact naming universe (experimental).
 *
 * Every participating artifact is renamed under a single set of session-wide decisions, so an API
 * symbol and the implementation and consumer references to it all follow the same rename and keep
 * linking. Renaming is chosen once here (not per artifact); each artifact still selects its own
 * platform and body passes through its own `--artifact-args` file, which uses the exact options of
 * `obfuscate` (the renaming options in that file are ignored, since rename is global). Sibling
 * artifacts form the naming universe and must not be listed on `--classpath`.
 *
 * Writing the output JARs is not a single transaction: a mid-run failure can leave only some
 * written. Per-artifact `--call-indirection`/`--prelink-calls` and integrity sealing are rejected,
 * because they would have to run after the global rename.
 */
@Command(
  name = "assemble", mixinStandardHelpOptions = true,
  description = ["Obfuscate several JARs in one cross-artifact naming universe so renamed API symbols stay consistent across artifacts (experimental)."],
  footer = ["Each --artifact-args FILE uses the same options as 'obfuscate' (via @file expansion); renaming options there are ignored."],
)
internal class AssembleCommand : Callable<Int> {
  @Spec lateinit var spec: CommandSpec

  @Option(names = ["--artifact", "-a"], required = true, paramLabel = "ID=INPUT[=OUTPUT]",
    description = ["An artifact: stable id, input JAR, and optional output JAR (default: INPUT-obfuscated.jar beside the input). Repeatable."])
  var artifacts: MutableList<String> = mutableListOf()

  @Option(names = ["--artifact-args"], paramLabel = "ID=FILE",
    description = ["Per-artifact body-pass options read from an args file, using the same options as 'obfuscate'. Repeatable. Renaming options in the file are ignored; rename is session-wide."])
  var artifactArgs: MutableList<String> = mutableListOf()

  @Option(names = ["--no-rename"], description = ["Disable class, method and field renaming for the whole program."])
  var noRename = false
  @Option(names = ["--no-rename-classes"], description = ["Disable class renaming."])
  var noRenameClasses = false
  @Option(names = ["--no-rename-methods"], description = ["Disable method renaming."])
  var noRenameMethods = false
  @Option(names = ["--no-rename-fields"], description = ["Disable field renaming."])
  var noRenameFields = false
  @Option(names = ["--flatten-packages"], description = ["Flatten renamed classes into --target-package across every artifact."])
  var flattenPackages = false
  @Option(names = ["--target-package"], paramLabel = "PACKAGE", description = ["Destination package; requires --flatten-packages."])
  var targetPackage: String? = null
  @Option(names = ["--application-package"], paramLabel = "PACKAGE", description = ["Extra application package root for the renamer; repeatable."])
  var applicationPackages: MutableList<String> = mutableListOf()
  @Option(names = ["--seed"], paramLabel = "N", description = ["Rename seed (body seeds stay per artifact)."])
  var seed: Long? = null
  @Option(names = ["--classpath"], paramLabel = "PATH", description = ["External dependency JAR or directory; repeatable. Sibling artifacts are in the union already and must not be listed."])
  var classpath: MutableList<Path> = mutableListOf()
  @Option(names = ["--extension"], paramLabel = "JAR", description = ["Platform or package provider JAR; repeatable."])
  var extensionJars: MutableList<Path> = mutableListOf()
  @Option(names = ["--mapping"], paramLabel = "FILE", description = ["Destination for the single cross-artifact mapping (default: assembly.mapping.txt beside the first output)."])
  var mapping: Path? = null
  @Option(names = ["--no-reproducible"], description = ["Allow non-reproducible output ordering."])
  var noReproducible = false

  override fun call(): Int {
    require(artifacts.isNotEmpty()) { "At least one --artifact is required" }
    val argsById = artifactArgs.associate { entry ->
      val id = entry.substringBefore('=', "")
      val file = entry.substringAfter('=', "")
      require(id.isNotBlank() && file.isNotBlank()) { "--artifact-args must be ID=FILE: $entry" }
      id to file
    }
    extensionJars.forEach { require(Files.isRegularFile(it)) { "Extension JAR does not exist: $it" } }

    val modules = artifacts.map { entry ->
      val parts = entry.split('=')
      require(parts.size in 2..3 && parts.all { it.isNotBlank() }) { "--artifact must be ID=INPUT[=OUTPUT]: $entry" }
      val id = parts[0]
      val input = Paths.get(parts[1])
      val output = if (parts.size == 3) Paths.get(parts[2])
      else input.resolveSibling(input.fileName.toString().removeSuffix(".jar") + "-obfuscated.jar")
      val config = argsById[id]?.let { file ->
        val command = ObfuscateCommand()
        CommandLine(command).parseArgs("@$file")
        command.configuration()
      } ?: ObfuscationConfig()
      AssemblyModule(id, input, output, config)
    }

    val renameConfig = ObfuscationConfig(
      renameClasses = !noRename && !noRenameClasses,
      renameMethods = !noRename && !noRenameMethods,
      renameFields = !noRename && !noRenameFields,
      flattenPackages = flattenPackages, targetPackage = targetPackage,
      applicationPackages = applicationPackages, seed = seed,
      reproducible = !noReproducible, classpath = classpath,
    )

    val result = withExtensionClassLoader(
      extensionJars.map { it.toUri().toURL() }, Thread.currentThread().contextClassLoader) {
      AssemblySession(modules, renameConfig).run()
    }

    val mappingPath = mapping ?: modules.first().output.resolveSibling("assembly.mapping.txt")
    mappingPath.parent?.let(Files::createDirectories)
    Files.write(mappingPath, result.mapping.toByteArray())

    val writer = spec.commandLine().out
    writer.println("Assembled ${result.outputs.size} artifacts in one naming universe")
    result.outputs.forEach { (id, path) -> writer.println("  $id -> $path") }
    writer.println("Mapping: $mappingPath")
    writer.flush()
    return 0
  }
}
