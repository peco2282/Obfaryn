package com.peco2282.obfaryn.api

/**
 * Represents a platform-specific extension that allows for custom behavior
 * or additional functionality to be implemented on a given platform.
 * This interface is intended to be a marker or base for defining platform
 * extensions in a system that requires platform-dependent features.
 */
interface PlatformExtension

/**
 * Enum class representing the various actions that can be applied to annotations
 * in the context of a policy framework.
 *
 * Each action corresponds to a specific directive that influences how
 * the annotated elements should be processed, manipulated, or treated
 * during code transformation or runtime behavior adjustments.
 */
enum class AnnotationPolicyAction {
  /**
   * Specifies that no obfuscation should be applied to the annotated element.
   *
   * This action ensures that the annotated element retains its original
   * name and structure during code transformation processes, such as
   * code shrinking or optimization. It is commonly used when the element
   * must maintain its original state for external accessibility, debugging,
   * or compliance with specific runtime requirements.
   */
  NO_OBFUSCATION,

  /**
   * Specifies that obfuscation should only rename the annotated element.
   *
   * This action restricts obfuscation to rename transformations without altering
   * the structure or behavior of the annotated element during code transformation
   * processes. It ensures the element's functional integrity is preserved while
   * modifying its name for obfuscation purposes.
   */
  RENAME_ONLY,

  /**
   * Specifies that the annotated element's name should be preserved as-is.
   *
   * Applying this action ensures that the name of the annotated element remains
   * unchanged during code transformation processes, such as obfuscation or optimization.
   * It is typically used in scenarios where the original name must be retained for
   * external references, interoperability, or other explicit requirements.
   */
  KEEP_NAME,

  /**
   * Specifies that the annotated element should be virtualized.
   *
   * This action ensures that the annotated element is transformed into a virtualized
   * representation during code transformation. Virtualization may involve abstracting
   * the concrete implementation details or altering the runtime behavior to operate
   * through an intermediate representation. This is commonly used to enhance flexibility,
   * enforce isolation, or facilitate runtime adaptations.
   */
  VIRTUALIZE,

  /**
   * Represents a policy action used to mark a critical code path
   * that is particularly important for performance or execution flow.
   * Applying this annotation can denote that the associated
   * component or function is highly optimized or relevant to the
   * core logic of the system.
   */
  HOT_PATH,
}

/**
 * Represents the various targets to which an annotation policy can be applied.
 * This enum specifies the elements of a program that can be annotated.
 *
 * Enum Constants:
 * - `CLASS`: Indicates that the annotation policy applies to classes.
 * - `METHOD`: Indicates that the annotation policy applies to methods.
 * - `FIELD`: Indicates that the annotation policy applies to fields.
 */
enum class AnnotationPolicyTarget { CLASS, METHOD, FIELD }

/**
 * Defines the contract for annotation policies that specify actions and targets
 * for code elements annotated with specific directives.
 *
 * Annotation policies guide how certain elements in the code should be treated
 * during runtime or compile-time transformations, such as obfuscation or optimization.
 *
 * Properties:
 * - `descriptor`: A descriptive string that identifies the purpose or intent of the annotation policy.
 * - `action`: Specifies the action to be applied to the annotated element. The action determines the
 *   treatment of the element, such as obfuscation, renaming, or virtualization.
 * - `targets`: A set of targets to which the annotation policy can be applied.
 *   Possible targets include classes, methods, and fields.
 * - `reason`: The rationale or explanation for applying the annotation policy, providing context for its use.
 */
interface AnnotationPolicy {
  val descriptor: String
  val action: AnnotationPolicyAction
  val targets: Set<AnnotationPolicyTarget>
  val reason: String
}

/**
 * Provides annotation policies to define and regulate behaviors or treatments
 * associated with annotated code elements within the system.
 *
 * This functional interface acts as a platform-specific extension to supply
 * a collection of `AnnotationPolicy` instances. These policies specify
 * the actions, targets, and rationale for handling code annotations, aiding
 * in processes such as optimization, obfuscation, or other transformations.
 *
 * Implementations of this interface should return a collection of relevant
 * `AnnotationPolicy` definitions tailored for the specific platform or
 * context in which they are intended to operate.
 */
fun interface AnnotationPolicyProvider : PlatformExtension {
  /**
   * Returns a collection of `AnnotationPolicy` instances that define actions,
   * targets, and rationales for handling annotated code elements.
   *
   * The policies provided serve as directives for transformations or behaviors
   * associated with annotations, such as optimization, obfuscation, or other
   * processing tasks. Each `AnnotationPolicy` outlines the treatment of
   * specific code elements, enabling consistent handling across the system.
   *
   * @return a collection of `AnnotationPolicy` objects specifying actions
   *         and targets for annotated code elements.
   */
  fun policies(): Collection<AnnotationPolicy>
}

/**
 * Represents a functional interface for contributing resources to a platform.
 *
 * A `ResourceContributor` defines a single method, `contribute`, which allows for
 * the generation or addition of resources in the context of a platform execution process.
 * Implementations are provided with contextual information about the current execution
 * environment, configurable platform options, and an output sink for storing generated resources.
 *
 * This interface is typically used within the platform infrastructure to extend, customize,
 * or provide additional artifacts or data during the execution lifecycle.
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
   * @param path The path used to identify the resource to be added.
   * @param content The binary content of the resource to be stored.
   */
  fun add(path: String, content: ByteArray)
}

/**
 * Defines an extension that contributes additional JVM class files to a platform during its execution.
 *
 * This interface allows platform-specific implementations to dynamically generate or provide
 * new classes based on the platform's current execution context and configuration options.
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
   * @param bytecode the byte array representing the JVM class file
   */
  fun add(bytecode: ByteArray)
}

/**
 * Represents the modes of contributing files or resources to an archive.
 *
 * Available modes:
 * - `MERGE`: Combines the files or resources into a single cohesive structure within the archive.
 * - `EMBED_AS_RESOURCE`: Includes the files or resources as independent entities within the archive.
 */
enum class ArchiveContributionMode { MERGE, EMBED_AS_RESOURCE }

/**
 * Represents a functional interface that allows for contributing archive data
 * to a platform-specific processing context.
 *
 * Implementers of this interface can define custom behaviors for generating
 * and contributing archives based on the given platform context, options, and
 * sink. The contributed archives can then be utilized by the platform for
 * further processing, embedding, or distribution.
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
   * @param archive The archive data to be added, represented as a byte array.
   * @param mode The mode of contribution, defining how the archive is incorporated (e.g., merged or embedded as a resource).
   * @param path The optional path for embedding the archive as a resource. Required only when the mode is `EMBED_AS_RESOURCE`.
   */
  fun add(archive: ByteArray, mode: ArchiveContributionMode, path: String?)

  /**
   * Adds an archive to the sink with the specified contribution mode.
   *
   * @param archive The archive data to be added, represented as a byte array.
   * @param mode The mode of contribution, defining how the archive is incorporated (e.g., merged or embedded as a resource).
   */
  fun add(archive: ByteArray, mode: ArchiveContributionMode) = add(archive, mode, null)
}
