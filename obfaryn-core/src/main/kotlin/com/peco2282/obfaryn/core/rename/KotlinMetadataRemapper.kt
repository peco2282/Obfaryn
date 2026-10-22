@file:Suppress("API_NOT_AVAILABLE", "DEPRECATION")
@file:OptIn(
  UnstableMetadataApi::class,
  ExperimentalContextReceivers::class,
  ExperimentalContextParameters::class,
  ExperimentalContracts::class,
)

package com.peco2282.obfaryn.core.rename

import org.objectweb.asm.commons.Remapper
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import kotlin.contracts.ExperimentalContracts
import kotlin.metadata.*
import kotlin.metadata.jvm.*

/** Strict metadata rewriting coordinated with JVM class and member mappings. Call [prepare] before modifying any classes. */
class KotlinMetadataRemapper private constructor(private val modules: Map<String, ByteArray>) {
  private val aliases = linkedMapOf<String, Pair<String, Boolean>>()
  private val companionClasses = mutableSetOf<String>()
  private var preparing = true
  companion object {
    private const val METADATA = "Lkotlin/Metadata;"

    /** Validates reading and writing without mutating inputs; throws with the offending path on failure. */
    fun prepare(nodes: Iterable<ClassNode>, resources: Map<String, ByteArray>): KotlinMetadataRemapper {
      val result = KotlinMetadataRemapper(resources.filterKeys { it.endsWith(".kotlin_module") }
        .mapValues { it.value.copyOf() })
      nodes.forEach { node ->
        result.annotations(node).forEach { annotation ->
          result.checked(node.name) { result.encode(annotation, node.name, emptyMap()) }
        }
      }
      result.rewriteModules(emptyMap())
      result.preparing = false
      return result
    }
  }

  internal fun isCompanionClass(name: String): Boolean = name in companionClasses

  /** Rewrites only the Metadata annotation. Accepts either an original or an ASM-remapped node. */
  fun rewrite(
    node: ClassNode,
    classMapping: Map<String, String>,
    methodMapping: Map<MemberKey, String> = emptyMap(),
    fieldMapping: Map<MemberKey, String> = emptyMap(),
    originalOwner: String = node.name,
  ) {
    val replacements = annotations(node).map { annotation ->
      annotation to checked(node.name) { encode(annotation, originalOwner, classMapping, methodMapping, fieldMapping) }
    }
    replacements.forEach { (annotation, values) -> annotation.values = values }
  }

  /** Returns replacement bytes for kotlin_module resources only; resource paths remain unchanged. */
  fun rewriteModules(classMapping: Map<String, String>): Map<String, ByteArray> = modules.mapValues { (path, bytes) ->
    checked(path) {
      val metadata = KotlinModuleMetadata.read(bytes)
      val preserved = validateModulePayload(bytes)
      // The public writer omits optional annotations. Keep their original protobufs,
      // but first ensure no class reference in them requires relocation.
      val checkedMapping = object : Map<String, String> by classMapping {
        override fun get(key: String): String? {
          val mapped = classMapping[key]
          require(mapped == null || mapped == key) {
            "Optional annotation class references relocated class $key -> $mapped"
          }
          return mapped
        }
      }
      val checker = Rewriter(checkedMapping, aliases)
      metadata.kmModule.optionalAnnotationClasses.forEach(checker::klass)
      val parts = linkedMapOf<String, KmPackageParts>()
      fun target(name: String): KmPackageParts = parts.getOrPut(name.substringBeforeLast('/', "").replace('/', '.')) {
        KmPackageParts(mutableListOf(), linkedMapOf())
      }
      metadata.kmModule.packageParts.values.forEach { pkg ->
        pkg.fileFacades.forEach { facade ->
          val mapped = classMapping[facade] ?: facade
          target(mapped).fileFacades.add(mapped)
        }
        pkg.multiFileClassParts.forEach { (part, facade) ->
          val mapped = classMapping[part] ?: part
          target(mapped).multiFileClassParts[mapped] = classMapping[facade] ?: facade
        }
      }
      metadata.kmModule.packageParts.clear()
      metadata.kmModule.packageParts.putAll(parts)
      metadata.write() + preserved
    }
  }

  private fun annotations(node: ClassNode): List<AnnotationNode> =
    (node.visibleAnnotations.orEmpty() + node.invisibleAnnotations.orEmpty()).filter { it.desc == METADATA }

  // Retain optional declarations and their lookup tables; reject unsupported consumers.
  private fun validateModulePayload(bytes: ByteArray): ByteArray {
    val preserved = ByteArrayOutputStream()
    val input = DataInputStream(ByteArrayInputStream(bytes))
    val count = input.readInt()
    require(count in 2..1024) { "Invalid module version header" }
    val version = IntArray(count) { input.readInt() }
    if (version[0] > 1 || version[0] == 1 && version[1] >= 4) {
      require(input.readInt() == 0) { "Module flags cannot be preserved by the public writer" }
    }
    fun varint(): Int {
      var value = 0
      for (shift in 0..28 step 7) {
        val byte = input.readUnsignedByte()
        value = value or ((byte and 127) shl shift)
        if (byte and 128 == 0) return value
      }
      error("Invalid module protobuf varint")
    }
    while (input.available() > 0) {
      val start = bytes.size - input.available()
      val tag = varint()
      val field = tag ushr 3
      require(field in setOf(1, 3, 4, 5, 16) && tag and 7 == 2) {
        "Module protobuf field ${tag ushr 3} cannot be preserved by the public writer"
      }
      val length = varint()
      require(length >= 0 && length <= input.available()) { "Invalid module protobuf length" }
      require(input.skipBytes(length) == length) { "Truncated module protobuf" }
      if (field in setOf(4, 5, 16)) {
        preserved.write(bytes, start, bytes.size - input.available() - start)
      }
    }
    return preserved.toByteArray()
  }

  private inline fun <T> checked(path: String, action: () -> T): T = try {
    action()
  } catch (e: Exception) {
    throw IllegalArgumentException("Cannot safely rewrite Kotlin metadata in $path: ${e.message}", e)
  }

  private fun encode(
    annotation: AnnotationNode, owner: String, mapping: Map<String, String>,
    methods: Map<MemberKey, String> = emptyMap(), fields: Map<MemberKey, String> = emptyMap(),
  ): MutableList<Any> {
    val entries = annotation.values.orEmpty().chunked(2).associate { it[0] as String to it[1] }
    fun ints(key: String): IntArray = when (val value = entries[key]) {
      null -> intArrayOf()
      is IntArray -> value
      is List<*> -> value.map { it as Int }.toIntArray()
      else -> error("Invalid $key")
    }
    fun strings(key: String): Array<String> = when (val value = entries[key]) {
      null -> emptyArray()
      is Array<*> -> value.map { it as String }.toTypedArray()
      is List<*> -> value.map { it as String }.toTypedArray()
      else -> error("Invalid $key")
    }
    val input = Metadata(
      kind = entries["k"] as? Int ?: 1,
      metadataVersion = ints("mv"),
      data1 = strings("d1"),
      data2 = strings("d2"),
      extraString = entries["xs"] as? String ?: "",
      packageName = entries["pn"] as? String ?: "",
      extraInt = entries["xi"] as? Int ?: 0,
    )
    val metadata = KotlinClassMetadata.readStrict(input)
    if (preparing) {
      if (metadata is KotlinClassMetadata.Class) {
        metadata.kmClass.companionObject?.let { companion ->
          companionClasses += metadata.kmClass.name.removePrefix(".").replace('.', '$') + "\$" + companion
        }
      }
      val container = when (metadata) {
        is KotlinClassMetadata.Class -> metadata.kmClass
        is KotlinClassMetadata.FileFacade -> metadata.kmPackage
        is KotlinClassMetadata.MultiFileClassPart -> metadata.kmPackage
        else -> null
      }
      val prefix = if (metadata is KotlinClassMetadata.Class) metadata.kmClass.name + "."
      else (input.packageName.takeIf { it.isNotEmpty() }?.replace('.', '/')
        ?: owner.substringBeforeLast('/', "")).let { if (it.isEmpty()) "" else "$it/" }
      container?.typeAliases?.forEach {
        aliases[prefix + it.name] = owner to (metadata is KotlinClassMetadata.Class)
      }
    }
    val visitor = Rewriter(mapping, aliases, owner, methods, fields,
      if (owner in companionClasses) owner.substringBeforeLast('$') else owner)
    when (metadata) {
      is KotlinClassMetadata.Class -> visitor.klass(metadata.kmClass)
      is KotlinClassMetadata.FileFacade -> visitor.pkg(metadata.kmPackage)
      is KotlinClassMetadata.MultiFileClassPart -> {
        visitor.pkg(metadata.kmPackage)
        metadata.facadeClassName = visitor.internal(metadata.facadeClassName)
      }
      is KotlinClassMetadata.MultiFileClassFacade ->
        metadata.partClassNames = metadata.partClassNames.map(visitor::internal)
      is KotlinClassMetadata.SyntheticClass -> metadata.kmLambda?.function?.let(visitor::function)
      is KotlinClassMetadata.Unknown -> error("Unknown metadata kind ${input.kind}")
    }
    // The public writer cannot emit versions before 1.4; retain their declarations in a supported format.
    if (metadata.version < JvmMetadataVersion(1, 4, 0)) metadata.version = JvmMetadataVersion(1, 4, 0)
    val output = metadata.write()
    val values = linkedMapOf<String, Any>().apply {
      putAll(entries)
      put("k", output.kind)
      put("mv", output.metadataVersion.toList())
      put("d1", output.data1.toList())
      put("d2", output.data2.toList())
      put("xi", output.extraInt)
      put("xs", output.extraString)
      if (input.packageName.isNotEmpty()) {
        put("pn", visitor.internal(owner).substringBeforeLast('/', "").replace('/', '.'))
      }
    }
    return values.flatMap { (key, value) -> listOf(key, value) }.toMutableList()
  }

  private class Rewriter(
    private val mapping: Map<String, String>,
    private val aliases: Map<String, Pair<String, Boolean>>,
    private val owner: String = "",
    private val methods: Map<MemberKey, String> = emptyMap(),
    private val fields: Map<MemberKey, String> = emptyMap(),
    private val fieldOwner: String = owner,
  ) {
    private val asm = object : Remapper() {
      override fun map(internalName: String): String = internal(internalName)
    }

    fun internal(name: String): String = mapping[name] ?: name

    private fun className(name: String): String {
      val local = name.startsWith('.')
      val original = name.removePrefix(".").replace('.', '$')
      val mapped = mapping[original] ?: return name
      return (if (local) "." else "") + mapped.replace('$', '.')
    }

    private fun aliasName(name: String): String {
      val (owner, nested) = aliases[name] ?: return name
      val mapped = internal(owner)
      return if (nested) mapped.replace('$', '.') + "." + name.substringAfterLast('.')
      else mapped.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" } + name.substringAfterLast('/')
    }

    fun klass(node: KmClass) {
      require(node.compilerPluginMetadata.isEmpty()) { "Opaque class compiler-plugin metadata is not supported" }
      val oldOwner = node.name.removePrefix(".").replace('.', '$')
      fun nested(name: String): String {
        val owner = internal(oldOwner)
        val child = internal("$oldOwner\$$name")
        require(child.startsWith("$owner\$")) { "Mapping breaks nested Kotlin class ownership: $oldOwner\$$name" }
        return child.removePrefix("$owner\$")
      }
      node.nestedClasses.replaceAll(::nested)
      node.companionObject = node.companionObject?.let(::nested)
      node.name = className(node.name)
      node.sealedSubclasses.replaceAll(::className)
      node.anonymousObjectOriginName = node.anonymousObjectOriginName?.let(::internal)
      node.typeParameters.forEach(::parameter)
      node.supertypes.forEach(::type)
      node.contextReceiverTypes.forEach(::type)
      node.inlineClassUnderlyingType?.let(::type)
      annotations(node.annotations)
      node.kmEnumEntries.forEach { annotations(it.annotations) }
      val propertyNames = node.properties.associate { it.name to propertyName(it) }
      if (node.isData) node.functions.filter { it.name == "copy" }.forEach { function ->
        function.valueParameters.forEach { parameter -> parameter.name = propertyNames[parameter.name] ?: parameter.name }
      }
      node.constructors.forEach {
        require(it.compilerPluginMetadata.isEmpty()) { "Opaque constructor compiler-plugin metadata is not supported" }
        it.signature = method(it.signature)
        it.valueParameters.forEach { parameter ->
          parameter.name = propertyNames[parameter.name] ?: parameter.name
          value(parameter)
        }
        annotations(it.annotations)
      }
      container(node)
      node.localDelegatedProperties.forEach(::property)
    }

    fun pkg(node: KmPackage) {
      container(node)
      node.localDelegatedProperties.forEach(::property)
    }

    private fun container(node: KmDeclarationContainer) {
      node.functions.forEach(::function)
      node.properties.forEach(::property)
      node.typeAliases.forEach {
        it.typeParameters.forEach(::parameter)
        type(it.underlyingType)
        type(it.expandedType)
        annotations(it.annotations)
      }
    }

    fun function(node: KmFunction) {
      require(node.compilerPluginMetadata.isEmpty()) { "Opaque function compiler-plugin metadata is not supported" }
      val renamed = methodName(node.signature)
      if (renamed != null) {
        node.name = renamed
        node.valueParameters.forEachIndexed { index, parameter -> parameter.name = "p$index" }
      }
      node.signature = method(node.signature)
      node.lambdaClassOriginName = node.lambdaClassOriginName?.let(::internal)
      node.typeParameters.forEach(::parameter)
      node.receiverParameterType?.let(::type)
      node.contextParameters.forEach(::value)
      node.valueParameters.forEach(::value)
      type(node.returnType)
      annotations(node.annotations)
      annotations(node.extensionReceiverParameterAnnotations)
      node.contract?.effects?.forEach {
        it.constructorArguments.forEach(::expression)
        it.conclusion?.let(::expression)
      }
    }

    private fun expression(node: KmEffectExpression) {
      node.isInstanceType?.let(::type)
      node.andArguments.forEach(::expression)
      node.orArguments.forEach(::expression)
    }

    private fun property(node: KmProperty) {
      require(node.compilerPluginMetadata.isEmpty()) { "Opaque property compiler-plugin metadata is not supported" }
      node.name = propertyName(node)
      node.fieldSignature = node.fieldSignature?.let {
        JvmFieldSignature(fields[MemberKey(fieldOwner, it.name, it.descriptor)] ?: it.name, asm.mapDesc(it.descriptor))
      }
      node.getterSignature = method(node.getterSignature)
      node.setterSignature = method(node.setterSignature)
      node.syntheticMethodForAnnotations = method(node.syntheticMethodForAnnotations)
      node.syntheticMethodForDelegate = method(node.syntheticMethodForDelegate)
      node.typeParameters.forEach(::parameter)
      node.receiverParameterType?.let(::type)
      node.contextParameters.forEach(::value)
      node.setterParameter?.let(::value)
      type(node.returnType)
      annotations(node.annotations)
      annotations(node.getter.annotations)
      node.setter?.let { annotations(it.annotations) }
      annotations(node.extensionReceiverParameterAnnotations)
    }

    private fun methodName(signature: JvmMethodSignature?): String? = signature?.let {
      methods[MemberKey(owner, it.name, it.descriptor)]
    }

    private fun propertyName(node: KmProperty): String {
      // Preserve the Kotlin property name when an accessor is kept (e.g. an API override).
      if (node.getterSignature?.let { methodName(it) == null } == true ||
        node.setterSignature?.let { methodName(it) == null } == true) return node.name
      // Accessor families share a name across interface implementations and overrides;
      // backing fields are renamed independently and cannot define the property identity.
      return methodName(node.getterSignature) ?: methodName(node.setterSignature)
        ?: node.fieldSignature?.let { fields[MemberKey(fieldOwner, it.name, it.descriptor)] } ?: node.name
    }

    private fun method(signature: JvmMethodSignature?): JvmMethodSignature? =
      signature?.let { JvmMethodSignature(methodName(it) ?: it.name, asm.mapMethodDesc(it.descriptor)) }

    private fun parameter(node: KmTypeParameter) {
      node.upperBounds.forEach(::type)
      annotations(node.annotations)
    }

    private fun value(node: KmValueParameter) {
      type(node.type)
      node.varargElementType?.let(::type)
      annotations(node.annotations)
    }

    private fun type(node: KmType) {
      node.classifier = when (val classifier = node.classifier) {
        is KmClassifier.Class -> KmClassifier.Class(className(classifier.name))
        is KmClassifier.TypeAlias -> KmClassifier.TypeAlias(aliasName(classifier.name))
        is KmClassifier.TypeParameter -> classifier
      }
      node.arguments.forEach { it.type?.let(::type) }
      node.abbreviatedType?.let(::type)
      node.outerType?.let(::type)
      node.flexibleTypeUpperBound?.type?.let(::type)
      annotations(node.annotations)
    }

    private fun annotations(nodes: MutableList<KmAnnotation>) {
      nodes.replaceAll(::annotation)
    }

    private fun annotation(node: KmAnnotation): KmAnnotation =
      KmAnnotation(className(node.className), node.arguments.mapValues { argument(it.value) })

    private fun argument(node: KmAnnotationArgument): KmAnnotationArgument = when (node) {
      is KmAnnotationArgument.KClassValue -> KmAnnotationArgument.KClassValue(className(node.className))
      is KmAnnotationArgument.ArrayKClassValue ->
        KmAnnotationArgument.ArrayKClassValue(className(node.className), node.arrayDimensionCount)
      is KmAnnotationArgument.EnumValue -> KmAnnotationArgument.EnumValue(className(node.enumClassName), node.enumEntryName)
      is KmAnnotationArgument.AnnotationValue -> KmAnnotationArgument.AnnotationValue(annotation(node.annotation))
      is KmAnnotationArgument.ArrayValue -> KmAnnotationArgument.ArrayValue(node.elements.map(::argument))
      else -> node
    }
  }
}
