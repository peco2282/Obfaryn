package com.peco2282.obfaryn.api

/**
 * Marker for capabilities returned by [Platform.extensions].
 *
 * Implement a supported subtype such as [AnnotationPolicyProvider], [ResourceContributor],
 * [ClassContributor], or [ArchiveContributor]. Extensions supply policy or content; this SPI
 * deliberately does not expose arbitrary bytecode transformers or source compilation.
 */
interface PlatformExtension

/**
 * Transformation intent associated with an annotation descriptor by an [AnnotationPolicy].
 *
 * Name preservation, body transformation permission, and virtualization selection are separate
 * concerns. Selecting virtualization does not guarantee bytecode eligibility, and keeping a name
 * does not by itself suppress body transformations.
 * Method policies may override class-level [RENAME_ONLY], [HOT_PATH], or [VIRTUALIZE] intent,
 * but not class-level [NO_OBFUSCATION]. Conflicting actions on the same declaration are rejected.
 */
enum class AnnotationPolicyAction {
  /**
   * Preserves names and suppresses method-body obfuscation for the annotated declaration.
   * A class-level policy also protects its members and cannot be overridden by a method policy.
   * References may still be remapped when other declarations are renamed; this is not a
   * byte-for-byte preservation guarantee.
   */
  NO_OBFUSCATION,

  /**
   * Allows renaming but suppresses method-body obfuscation, including virtualization.
   * Renaming remains subject to other keep rules and safety checks; it is not forced.
   */
  RENAME_ONLY,

  /**
   * Preserves the annotated declaration's name while allowing body transformations.
   * Keeping a class name does not automatically preserve the names of all its members.
   */
  KEEP_NAME,

  /**
   * Selects methods for virtualization, subject to exclusions and bytecode eligibility checks.
   * A class policy supplies a default for its methods; it does not make unsupported bytecode eligible.
   */
  VIRTUALIZE,

  /**
   * Suppresses ordinary method-body transformations, including virtualization, for latency-sensitive code.
   * Renaming remains allowed. A method-level [VIRTUALIZE] may override a class-level hot-path default.
   */
  HOT_PATH,
}

/**
 * Declaration kinds on which a policy's annotation descriptor is recognized.
 * Policies are ignored on declarations outside their [AnnotationPolicy.targets].
 */
enum class AnnotationPolicyTarget { CLASS, METHOD, FIELD }

/**
 * Maps a binary annotation to Obfaryn transformation intent without depending on its annotation class.
 *
 * Annotation descriptors use JVM syntax, not dotted class names. The annotation must be present
 * in the input bytecode: source-retained annotations cannot be detected by this policy.
 *
 * @see AnnotationPolicyProvider
 */
interface AnnotationPolicy {
  /** JVM annotation descriptor, for example `Lcom/example/Keep;`. */
  val descriptor: String

  /** Transformation intent associated with matching annotations. */
  val action: AnnotationPolicyAction

  /** Declaration kinds on which [descriptor] should be recognized. */
  val targets: Set<AnnotationPolicyTarget>

  /** Human-readable explanation included in policy diagnostics. */
  val reason: String
}

/**
 * Adds platform-specific annotation mappings, for example for third-party framework annotations.
 * Return this extension from [Platform.extensions]; adding policies does not require adding the
 * mapped annotation classes to Obfaryn's own annotation module.
 * Policies are applied after content contributions, including to contributed classes. Both
 * runtime-visible and runtime-invisible annotations are recognized. Field policies support
 * [AnnotationPolicyAction.NO_OBFUSCATION] and [AnnotationPolicyAction.KEEP_NAME]; method-body
 * and virtualization actions have no field-level effect.
 */
fun interface AnnotationPolicyProvider : PlatformExtension {
  /**
   * Returns the annotation mappings contributed by this extension.
   * All policies for a given descriptor must agree on the action, even with different target sets.
   *
   * @return Policies using JVM descriptors and explicit target kinds, or an empty collection.
   */
  fun policies(): Collection<AnnotationPolicy>
}

/**
 * Contributes non-class archive entries, such as generated configuration or service descriptors.
 * Use [ClassContributor] for class files and [ArchiveContributor] for whole archives.
 * Contributions are applied before the transformation pipeline and are visible to later extensions.
 */
fun interface ResourceContributor : PlatformExtension {
  /**
   * Contributes resources to a platform execution process by utilizing the provided context,
   * configurable platform options, and an output sink for resource storage.
   *
   * @param context The execution environment context, providing access to loaded classes and resources.
   * @param options The configurable platform options, enabling retrieval and enforcement of option values.
   * @param sink The resource sink used for adding or storing generated resources during the contribution process.
   */
  fun contribute(context: PlatformContext, options: PlatformOptions, sink: ResourceSink)
}

/**
 * Functional interface representing a sink for storing resources.
 *
 * Implementers of this interface define how resources, identified by a path and provided as binary content,
 * are processed or stored.
 */
fun interface ResourceSink {
  /**
   * Adds a resource to the sink, identified by a specified path and represented as binary content.
   *
   * The core sink copies [content] and rejects duplicate resource paths instead of replacing them.
   * Paths must be non-blank and relative, must not start with `/` or `\`, and must not contain
   * empty, `.` or `..` slash-separated segments. Paths ending in `.class` must use [ClassSink].
   *
   * @param path The path used to identify the resource to be added.
   * @param content The binary content of the resource to be stored.
   * @throws IllegalArgumentException In the core sink, if the path is invalid, denotes a class,
   * or is already present in the resource map.
   */
  fun add(path: String, content: ByteArray)
}

/**
 * Contributes complete compiled JVM class files before the transformation pipeline runs.
 * Contributed classes participate in normal renaming, body transformations, frame calculation,
 * and verification; contribution does not implicitly keep their names or bodies.
 */
fun interface ClassContributor : PlatformExtension {
  /**
   * Contributes additional JVM class files to the platform during its execution based on
   * the provided execution context, platform options, and a sink for adding generated classes.
   *
   * @param context The execution environment context, providing access to loaded classes and resources.
   * @param options The configurable platform options used to determine contribution behavior.
   * @param sink The sink used for emitting additional JVM class files.
   */
  fun contribute(context: PlatformContext, options: PlatformOptions, sink: ClassSink)
}

/**
 * A functional interface for handling JVM class files.
 *
 * Implementations of this interface are designed to process or consume
 * complete JVM class files represented as byte arrays. The internal name
 * of the class file is read and validated by the core processing logic.
 */
fun interface ClassSink {
  /**
   * Adds a JVM class file represented as a byte array for processing.
   *
   * The core sink parses a fresh ASM node and identifies it by the class file's internal name,
   * not by a caller-supplied archive path. A class with that name must not already exist.
   *
   * @param bytecode the byte array representing the JVM class file
   * @throws IllegalArgumentException In the core sink, if the class name is already present
   * or the class reader cannot be constructed from [bytecode].
   */
  fun add(bytecode: ByteArray)
}

/**
 * Determines whether an archive's contents enter the pipeline or remain opaque resource data.
 *
 * [MERGE] imports classes and non-class entries into the input context. [EMBED_AS_RESOURCE]
 * stores the archive itself at an explicit path without transforming its nested contents.
 * The core merge rejects duplicate class/resource keys, `module-info.class`, multi-release entries,
 * and JAR signature files; manifests are not imported. Embedded content is not parsed as an archive.
 */
enum class ArchiveContributionMode { MERGE, EMBED_AS_RESOURCE }

/**
 * Contributes JAR/ZIP bytes either by merging entries or embedding the archive as a resource.
 * Select the behavior for each emitted archive through [ArchiveSink.add].
 */
fun interface ArchiveContributor : PlatformExtension {
  /**
   * Contributes archive data to the specified platform context using the provided options
   * and archive sink. This method defines the behavior for generating and adding archives
   * to the platform, enabling further processing or distribution.
   *
   * @param context The platform context that provides access to loaded classes, resources,
   *                and other relevant platform-specific information.
   * @param options The platform options that define configurable parameters and settings
   *                used in the archive contribution process.
   * @param sink    The archive sink that receives the generated archive data along with
   *                its contribution mode and optional path.
   */
  fun contribute(context: PlatformContext, options: PlatformOptions, sink: ArchiveSink)
}

/**
 * Functional interface representing a sink for adding archives with specified contribution modes.
 *
 * This interface enables handling archives by defining how they should be incorporated into a larger
 * structure or system, such as merging them or embedding them as distinct resources.
 */
fun interface ArchiveSink {
  /**
   * Adds an archive to the sink with the specified contribution mode and optional path.
   *
   * In the core sink, merged entries follow [ClassSink] and [ResourceSink] validation. Embedded
   * bytes follow resource validation and copying without inspection of the archive contents.
   * Earlier contributions are not rolled back if processing this archive fails.
   *
   * @param archive The archive data to be added, represented as a byte array.
   * @param mode The mode of contribution, defining how the archive is incorporated (e.g., merged or embedded as a resource).
   * @param path Required resource path for [ArchiveContributionMode.EMBED_AS_RESOURCE]; ignored for [ArchiveContributionMode.MERGE].
   * @throws IllegalArgumentException In the core sink, if the embedding path is absent or invalid,
   * an entry conflicts with existing content, or a merged entry is forbidden.
   */
  fun add(archive: ByteArray, mode: ArchiveContributionMode, path: String?)

  /**
   * Adds an archive to the sink with the specified contribution mode.
   * Delegates with a `null` path, so use this overload for [ArchiveContributionMode.MERGE].
   * [ArchiveContributionMode.EMBED_AS_RESOURCE] requires the overload with an explicit path.
   *
   * @param archive The archive data to be added, represented as a byte array.
   * @param mode The mode of contribution, defining how the archive is incorporated (e.g., merged or embedded as a resource).
   */
  fun add(archive: ByteArray, mode: ArchiveContributionMode) = add(archive, mode, null)
}
