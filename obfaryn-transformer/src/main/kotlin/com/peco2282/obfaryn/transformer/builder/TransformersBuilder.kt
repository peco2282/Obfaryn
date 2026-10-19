package com.peco2282.obfaryn.transformer.builder

import com.peco2282.obfaryn.api.transformer.Transformer
import com.peco2282.obfaryn.transformer.ConstantFieldTransformer
import com.peco2282.obfaryn.transformer.MetadataStripTransformer
import com.peco2282.obfaryn.transformer.NumberObfuscationTransformer
import com.peco2282.obfaryn.transformer.StringEncryptionStrategy
import com.peco2282.obfaryn.transformer.StringEncryptionTransformer
import com.peco2282.obfaryn.transformer.XorStringEncryptionStrategy
import java.util.Random
import kotlin.random.asJavaRandom
import kotlin.random.Random as KotlinRandom

fun transformers(builder: TransformersBuilder.() -> Unit): List<Transformer> =
  TransformersBuilder().apply(builder).build()

class TransformersBuilder {
  private val transformers: MutableList<Transformer> = mutableListOf()
  private val constantOptions = mutableMapOf<Transformer, Pair<Boolean, Boolean>>()

  fun string(configure: StringBuilder.() -> Unit = {}): TransformersBuilder = apply {
    require(transformers.none { it.id == "string-encryption" }) { "string is already configured" }
    val options = DefaultStringBuilder().apply(configure)
    val transformer = StringEncryptionTransformer(options.random, options.strategy)
    transformers.add(transformer)
    constantOptions[transformer] = options.includeConstantFields to false
  }

  fun number(configure: NumberBuilder.() -> Unit = {}): TransformersBuilder = apply {
    require(transformers.none { it.id == "number-obfuscation" }) { "number is already configured" }
    val options = DefaultNumberBuilder().apply(configure)
    val transformer = NumberObfuscationTransformer(options.random)
    transformers.add(transformer)
    constantOptions[transformer] = false to options.includeConstantFields
  }

  fun add(transformer: Transformer): TransformersBuilder = apply {
    transformers.add(transformer)
  }

  fun metadata(configure: MetadataBuilder.() -> Unit = {}): TransformersBuilder = apply {
    this += DefaultMetadataBuilder().apply(configure).build()
  }

  operator fun plusAssign(transformer: Transformer) {
    transformers.add(transformer)
  }

  fun remove(transformer: Transformer): TransformersBuilder = apply {
    transformers.remove(transformer)
  }

  operator fun minusAssign(transformer: Transformer) {
    transformers.remove(transformer)
  }

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

interface StringBuilder {
  var strategy: StringEncryptionStrategy
  var random: Random
  var includeConstantFields: Boolean
}

internal class DefaultStringBuilder : StringBuilder {
  override var strategy: StringEncryptionStrategy = XorStringEncryptionStrategy()
  override var random: Random = KotlinRandom.asJavaRandom()
  override var includeConstantFields = true
}

interface NumberBuilder {
  var random: Random
  var includeConstantFields: Boolean
}

internal class DefaultNumberBuilder : NumberBuilder {
  override var random: Random = KotlinRandom.asJavaRandom()
  override var includeConstantFields = true
}

interface MetadataBuilder {
  var stripDebugInfo: Boolean
  var stripKotlinMetadata: Boolean
  var stripGenericSignatures: Boolean
  var stripMethodParameters: Boolean
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
