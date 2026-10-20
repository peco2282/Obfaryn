package com.peco2282.obfaryn.transformer.builder

import com.peco2282.obfaryn.api.transformer.StringEncryptionCodec
import com.peco2282.obfaryn.api.transformer.NumberObfuscationStrategy
import com.peco2282.obfaryn.api.transformer.NumberObfuscationSelector
import com.peco2282.obfaryn.api.transformer.Transformer
import com.peco2282.obfaryn.transformer.ConstantFieldTransformer
import com.peco2282.obfaryn.transformer.DefaultNumberObfuscationStrategy
import com.peco2282.obfaryn.transformer.DefaultNumberObfuscationSelector
import com.peco2282.obfaryn.transformer.MetadataStripTransformer
import com.peco2282.obfaryn.transformer.NumberObfuscationTransformer
import com.peco2282.obfaryn.transformer.StringEncryptionTransformer
import com.peco2282.obfaryn.transformer.XorStringEncryptionCodec
import java.util.Random
import kotlin.random.asJavaRandom
import kotlin.random.Random as KotlinRandom


/**
 * Builds a list of [Transformer] instances using a DSL configuration block.
 *
 * @param builder A lambda configuring the [TransformersBuilder].
 * @return An immutable list of configured [Transformer] instances.
 */
fun transformers(builder: TransformersBuilder.() -> Unit): List<Transformer> =
  TransformersBuilder().apply(builder).build()

/**
 * DSL builder for configuring and assembling a pipeline of bytecode [Transformer] instances.
 *
 * Provides builder functions for standard transformations such as string encryption,
 * number obfuscation, and metadata stripping, with support for automatic constant field
 * extraction when enabled.
 */
class TransformersBuilder {
  private val transformers: MutableList<Transformer> = mutableListOf()
  private val constantOptions = mutableMapOf<Transformer, Pair<Boolean, Boolean>>()

  /**
   * Configures and adds a string encryption transformer to the pipeline.
   *
   * Only one string encryption transformer can be configured per builder.
   *
   * @param configure Configuration block for [StringBuilder] options.
   * @return This builder instance for chaining.
   * @throws IllegalArgumentException If a string encryption transformer is already configured.
   */
  fun string(configure: StringBuilder.() -> Unit = {}): TransformersBuilder = apply {
    require(transformers.none { it.id == "string-encryption" }) { "string is already configured" }
    val options = DefaultStringBuilder().apply(configure)
    val transformer = StringEncryptionTransformer(options.random, options.codec)
    transformers.add(transformer)
    constantOptions[transformer] = options.includeConstantFields to false
  }

  /**
   * Configures and adds a number obfuscation transformer to the pipeline.
   *
   * Only one number obfuscation transformer can be configured per builder.
   *
   * @param configure Configuration block for [NumberBuilder] options.
   * @return This builder instance for chaining.
   * @throws IllegalArgumentException If a number obfuscation transformer is already configured.
   */
  fun number(configure: NumberBuilder.() -> Unit = {}): TransformersBuilder = apply {
    require(transformers.none { it.id == "number-obfuscation" }) { "number is already configured" }
    val options = DefaultNumberBuilder().apply(configure)
    val transformer = NumberObfuscationTransformer(options.random, options.strategy, options.selector)
    transformers.add(transformer)
    constantOptions[transformer] = false to options.includeConstantFields
  }

  /**
   * Adds an existing [Transformer] instance to the pipeline.
   *
   * @param transformer The transformer to add.
   * @return This builder instance for chaining.
   */
  fun add(transformer: Transformer): TransformersBuilder = apply {
    transformers.add(transformer)
  }

  /**
   * Configures and adds a metadata stripping transformer to the pipeline.
   *
   * @param configure Configuration block for [MetadataBuilder] options.
   * @return This builder instance for chaining.
   */
  fun metadata(configure: MetadataBuilder.() -> Unit = {}): TransformersBuilder = apply {
    this += DefaultMetadataBuilder().apply(configure).build()
  }

  /**
   * Appends a [Transformer] instance to the pipeline.
   *
   * @param transformer The transformer to add.
   */
  operator fun plusAssign(transformer: Transformer) {
    transformers.add(transformer)
  }

  /**
   * Removes a [Transformer] instance from the pipeline.
   *
   * @param transformer The transformer to remove.
   * @return This builder instance for chaining.
   */
  fun remove(transformer: Transformer): TransformersBuilder = apply {
    transformers.remove(transformer)
  }

  /**
   * Removes a [Transformer] instance from the pipeline.
   *
   * @param transformer The transformer to remove.
   */
  operator fun minusAssign(transformer: Transformer) {
    transformers.remove(transformer)
  }

  /**
   * Builds and returns the configured list of [Transformer] instances.
   *
   * If constant field handling is enabled for string encryption or number obfuscation,
   * a [ConstantFieldTransformer] is automatically inserted ahead of dependent transformers.
   *
   * @return The constructed list of transformers.
   * @throws IllegalArgumentException If [ConstantFieldTransformer] was manually added while automatic inclusion is enabled.
   */
  fun build(): List<Transformer> {
    val strings = transformers.any { constantOptions[it]?.first == true }
    val numbers = transformers.any { constantOptions[it]?.second == true }
    return buildList {
      if (strings || numbers) {
        require(transformers.none { it.id == "constant-fields" }) {
          "constant-fields is generated automatically; disable includeConstantFields to supply it manually"
        }
        add(ConstantFieldTransformer(strings = strings, numbers = numbers))
      }
      addAll(transformers)
    }
  }
}

/**
 * Configuration builder interface for string encryption.
 */
interface StringBuilder {
  /** Paired build-time encryption and runtime decoder generation. Defaults to XOR. */
  var codec: StringEncryptionCodec

  /**
   * The random number generator used for key generation.
   */
  var random: Random

  /**
   * Whether to extract static final constant fields into `<clinit>` for string encryption.
   */
  var includeConstantFields: Boolean
}

internal class DefaultStringBuilder : StringBuilder {
  override var codec: StringEncryptionCodec = XorStringEncryptionCodec()
  override var random: Random = KotlinRandom.asJavaRandom()
  override var includeConstantFields = true
}

/**
 * Configuration builder interface for number obfuscation.
 */
interface NumberBuilder {
  /** Typed expression generation; null results skip a constant. */
  var strategy: NumberObfuscationStrategy
  /** Target selection, including annotations or random sampling. Runs before expression generation. */
  var selector: NumberObfuscationSelector
  /**
   * The random number generator used for numeric transformations.
   */
  var random: Random

  /**
   * Whether to extract static final constant fields into `<clinit>` for number obfuscation.
   */
  var includeConstantFields: Boolean
}

internal class DefaultNumberBuilder : NumberBuilder {
  override var strategy: NumberObfuscationStrategy = DefaultNumberObfuscationStrategy()
  override var selector: NumberObfuscationSelector = DefaultNumberObfuscationSelector()
  override var random: Random = KotlinRandom.asJavaRandom()
  override var includeConstantFields = true
}

/**
 * Configuration builder interface for class metadata and debug information stripping.
 */
interface MetadataBuilder {
  /**
   * Whether to strip debug information including line numbers and local variable tables.
   */
  var stripDebugInfo: Boolean

  /**
   * Whether to strip Kotlin `@Metadata` annotations.
   */
  var stripKotlinMetadata: Boolean

  /**
   * Whether to strip generic signatures from classes, methods, and fields.
   */
  var stripGenericSignatures: Boolean

  /**
   * Whether to strip parameter name metadata from methods.
   */
  var stripMethodParameters: Boolean

  /**
   * Creates a [Transformer] configured with the specified metadata stripping options.
   *
   * @return A configured metadata strip transformer.
   */
  fun build(): Transformer
}

internal class DefaultMetadataBuilder : MetadataBuilder {
  override var stripDebugInfo = true
  override var stripKotlinMetadata = false
  override var stripGenericSignatures = false
  override var stripMethodParameters = true
  override fun build(): Transformer = MetadataStripTransformer(
    stripDebugInfo = stripDebugInfo, stripKotlinMetadata = stripKotlinMetadata,
    stripGenericSignatures = stripGenericSignatures, stripMethodParameters = stripMethodParameters,
  )
}
