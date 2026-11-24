@file:Suppress("unused")

package com.peco2282.obfaryn.gradle

import com.peco2282.obfaryn.core.ObfuscationConfig
import com.peco2282.obfaryn.core.io.ArtifactPaths
import com.peco2282.obfaryn.core.withExtensionClassLoader
import com.peco2282.obfaryn.standard.AssemblyModule
import com.peco2282.obfaryn.standard.AssemblySession
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import java.nio.file.Files
import java.nio.file.Path

/**
 * Root-level configuration for a cross-artifact assembly (experimental).
 *
 * Lists the subprojects whose JARs are obfuscated together in one naming universe, and the
 * session-wide renaming decisions (owned by the whole program, not any one artifact). Each listed
 * subproject keeps configuring its own body passes through its own `obfaryn { }` block.
 *
 * @property rename class/method/field renaming for the whole program.
 * @property flattenPackages move eligible renamed classes into [targetPackage] across every artifact.
 * @property applicationPackages extra package roots treated as application scope by the renamer.
 * @property seed rename seed; body seeds stay per artifact.
 * @property classpath extra external dependencies for hierarchy/frame resolution (sibling artifacts
 *   are added automatically and must not be listed here).
 * @property outputDirectory a shared destination directory, or null to write each artifact's JAR
 *   next to its own `build/libs`.
 * @property outputSuffix appended to each artifact's JAR name.
 * @property mapping destination for the single cross-artifact mapping; null uses
 *   `build/obfaryn/assembly.mapping.txt` under the root project.
 */
open class ObfarynAssemblyExtension {
  internal val artifacts = mutableListOf<Project>()

  /** Adds a subproject to the assembly. Order does not matter; ids are the project paths. */
  fun artifact(project: Project) { artifacts += project }

  val rename = RenameOptions()
  fun rename(action: Action<RenameOptions>) = action.execute(rename)
  var flattenPackages = false
  var targetPackage: String? = null
  var applicationPackages = listOf<String>()
  var seed: Long? = null
  var reproducible = true
  var classpath = listOf<String>()
  var outputDirectory: String? = null
  var outputSuffix = "obfuscated"
  var mapping: String? = null
}

private fun Project.jarTask(): AbstractArchiveTask? =
  (tasks.findByName("shadowJar") ?: tasks.findByName("jar")) as? AbstractArchiveTask

private fun Project.resolvedDependencies(): List<Path> =
  listOf("runtimeClasspath", "compileClasspath").mapNotNull(configurations::findByName)
    .flatMap { it.files }.map { it.toPath() }.distinct()

/** Executes the assembly task: collects each subproject's JAR and settings, then runs one session. */
internal fun runAssembly(root: Project, assembly: ObfarynAssemblyExtension) {
  require(assembly.artifacts.isNotEmpty()) {
    "obfarynAssembly needs at least one artifact(project(\"...\"))"
  }
  val inputs = assembly.artifacts.associateWith { artifact ->
    val jar = artifact.jarTask() ?: error("Project ${artifact.path} has no jar/shadowJar task to assemble")
    jar.archiveFile.get().asFile.toPath()
  }
  val siblings = inputs.values.toList()
  fun withoutSiblings(paths: List<Path>) = paths.filterNot { p -> siblings.any { ArtifactPaths.sameFile(it, p) } }

  val modules = assembly.artifacts.map { artifact ->
    val extension = artifact.extensions.findByType(ObfarynExtension::class.java)
    val dependencies = withoutSiblings(artifact.resolvedDependencies())
    val config = extension?.toObfuscationConfig(artifact, dependencies)
      ?: ObfuscationConfig(classpath = dependencies)
    val input = inputs.getValue(artifact)
    val directory = assembly.outputDirectory?.let(root::file)
      ?: artifact.layout.buildDirectory.dir("libs").get().asFile
    val output = directory.resolve(input.fileName.toString().removeSuffix(".jar") + "-${assembly.outputSuffix}.jar")
    AssemblyModule(artifact.path, input, output.toPath(), config)
  }

  val external = withoutSiblings(
    assembly.artifacts.flatMap { it.resolvedDependencies() } + assembly.classpath.map { root.file(it).toPath() })
    .distinct().sortedBy { it.toString() }
  val renameConfig = ObfuscationConfig(
    renameClasses = assembly.rename.classes,
    renameMethods = assembly.rename.methods,
    renameFields = assembly.rename.fields,
    flattenPackages = assembly.flattenPackages,
    targetPackage = assembly.targetPackage,
    applicationPackages = assembly.applicationPackages,
    seed = assembly.seed,
    reproducible = assembly.reproducible,
    classpath = external,
  )

  val platformFiles = assembly.artifacts.flatMap { artifact ->
    artifact.configurations.findByName("obfarynPlatform")?.files?.map { it.toURI().toURL() }.orEmpty()
  }.distinct()
  val result = withExtensionClassLoader(platformFiles, ObfarynPlugin::class.java.classLoader) {
    AssemblySession(modules, renameConfig).run()
  }

  val mappingPath = assembly.mapping?.let(root::file)?.toPath()
    ?: root.layout.buildDirectory.file("obfaryn/assembly.mapping.txt").get().asFile.toPath()
  mappingPath.parent?.let(Files::createDirectories)
  Files.write(mappingPath, result.mapping.toByteArray())

  root.logger.lifecycle("Obfaryn assembly: ${result.outputs.size} artifacts in one naming universe; mapping: $mappingPath")
  result.outputs.forEach { (id, path) -> root.logger.lifecycle("Obfaryn assembly: $id -> $path") }
}
