package com.peco2282.obfaryn.core.transformer

import com.peco2282.obfaryn.core.annotation.ObfuscationAnnotationPolicy
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

/** How selected methods that cannot be virtualized affect the run. */
enum class VirtualizationSelectionMode { STRICT, ELIGIBLE, ANALYZE_ONLY }

/** A method's original JVM identity, using an internal owner name and a JVM descriptor. */
data class MethodIdentity(val owner: String, val name: String, val descriptor: String) {
  override fun toString(): String = "$owner.$name$descriptor"
}

enum class VirtualizationRejectionReason {
  INITIALIZER, NATIVE_METHOD, ABSTRACT_METHOD, SYNCHRONIZED_METHOD, UNSUPPORTED_TYPE,
  EXCEPTION_HANDLER, EMPTY_BODY, UNSUPPORTED_OPCODE, UNSUPPORTED_CONSTANT,
  CONSTRUCTOR_INVOCATION, INVALID_PROGRAM
}

enum class VirtualizationRejectionCode {
  CONSTRUCTOR, CLASS_INITIALIZER, NATIVE_METHOD, ABSTRACT_METHOD, SYNCHRONIZED_METHOD,
  MONITOR, INVOKEDYNAMIC, LEGACY_SUBROUTINE, INVALID_CONTROL_FLOW, INVALID_STACK,
  UNSUPPORTED_TYPE, EXCEPTION_HANDLER, EMPTY_BODY, UNSUPPORTED_OPCODE,
  UNSUPPORTED_CONSTANT, CONSTRUCTOR_INVOCATION, INVALID_PROGRAM
}

data class VirtualizationRejection(
  val reason: VirtualizationRejectionReason,
  val detail: String,
  val instructionIndex: Int? = null,
  val opcode: Int? = null,
  val code: VirtualizationRejectionCode = when (reason) {
    VirtualizationRejectionReason.INITIALIZER -> VirtualizationRejectionCode.CONSTRUCTOR
    else -> VirtualizationRejectionCode.valueOf(reason.name)
  },
)

/**
 * Read-only eligibility result for one original method.
 *
 * [selected] is false when no selector matched or a performance policy excluded the method.
 * [rejections] records unsupported instructions and their locations for selected methods.
 * Counts ignore labels, frames, and line-number nodes.
 */
data class VirtualizationMethodAnalysis(
  val identity: String,
  val selected: Boolean,
  val exclusionReason: String? = null,
  val reasons: List<String>,
  val methodIdentity: MethodIdentity? = null,
  val rejections: List<VirtualizationRejection> = emptyList(),
  val fields: List<String> = emptyList(),
  val invokes: List<String> = emptyList(),
  val references: List<String> = emptyList(),
  val opcodes: Map<Int, Int> = emptyMap(),
  val types: List<String> = emptyList(),
  val estimatedInstructionCount: Int = 0,
  val bridgeCount: Int = 0,
  val isStatic: Boolean = false,
  val argumentTypes: List<String> = emptyList(),
  val returnType: String = "V",
  val hasTryCatch: Boolean = false,
  val hasInvokeDynamic: Boolean = false,
) {
  val owner: String? get() = methodIdentity?.owner
  val name: String? get() = methodIdentity?.name
  val descriptor: String? get() = methodIdentity?.descriptor
  val eligible: Boolean get() = reasons.isEmpty()
  val instructionCount: Int get() = opcodes.values.sum()
  val fieldCount: Int get() = (Opcodes.GETSTATIC..Opcodes.PUTFIELD).sumOf { opcodes[it] ?: 0 }
  val invokeCount: Int get() = (Opcodes.INVOKEVIRTUAL..Opcodes.INVOKEDYNAMIC).sumOf { opcodes[it] ?: 0 }
}

/**
 * Deterministic schema-2 summary of method selection and eligibility.
 *
 * [rejected] counts selected methods with at least one rejection; [excludedByPolicy] counts
 * performance-policy exclusions. The report contains original names and should be handled as
 * build information.
 */
data class VirtualizationAnalysisReport(
  val methods: List<VirtualizationMethodAnalysis>,
  val classesAnalyzed: Int = methods.mapNotNull { it.owner }.distinct().size,
) {
  val schemaVersion: Int get() = 2
  val methodsAnalyzed: Int get() = methods.size
  val rejectionSummary: Map<VirtualizationRejectionCode, Int> get() = methods.flatMap { it.rejections }
    .groupingBy { it.code }.eachCount().toSortedMap(compareBy { it.name })
  val total: Int get() = methods.size
  val unselected: Int get() = total - selected
  val selected: Int get() = methods.count { it.selected }
  val eligible: Int get() = methods.count { it.selected && it.eligible }
  val rejected: Int get() = methods.count { it.selected && !it.eligible }
  val excludedByPolicy: Int get() = methods.count { it.exclusionReason != null }
  val fields: List<String> get() = methods.flatMap { it.fields }.distinct().sorted()
  val invokes: List<String> get() = methods.flatMap { it.invokes }.distinct().sorted()
  val references: List<String> get() = methods.flatMap { it.references }.distinct().sorted()
  val types: List<String> get() = methods.flatMap { it.types }.distinct().sorted()
  val opcodes: Map<Int, Int> get() = methods.flatMap { it.opcodes.entries }
    .groupBy { it.key }.mapValues { (_, entries) -> entries.sumOf { it.value } }.toSortedMap()
  val instructionCount: Int get() = methods.sumOf { it.instructionCount }
  val fieldCount: Int get() = methods.sumOf { it.fieldCount }
  val invokeCount: Int get() = methods.sumOf { it.invokeCount }
  val estimatedInstructionCount: Int get() = methods.filter { it.selected && it.eligible }.sumOf { it.estimatedInstructionCount }
  val bridgeCount: Int get() = methods.filter { it.selected && it.eligible }.sumOf { it.bridgeCount }

  /**
   * Enforces [mode] and returns human-readable warnings.
   *
   * [VirtualizationSelectionMode.STRICT] throws for every ineligible selected method;
   * [VirtualizationSelectionMode.ELIGIBLE] reports skipped methods; analysis-only does not fail.
   */
  fun requireEligible(mode: VirtualizationSelectionMode): List<String> {
    val failures = methods.filter { it.selected && !it.eligible }
      .map { "Cannot virtualize ${it.identity}: ${it.reasons.joinToString("; ")}" }
    if (mode == VirtualizationSelectionMode.STRICT && failures.isNotEmpty())
      throw IllegalArgumentException(failures.joinToString("\n"))
    return if (selected == 0) listOf("Virtualization selectors matched no non-excluded methods")
      else if (mode == VirtualizationSelectionMode.ELIGIBLE) failures.map { "Skipped: $it" } +
        (if (eligible == 0) listOf("Virtualization selectors matched $selected methods but no eligible methods") else emptyList())
      else emptyList()
  }

  /** Serializes this report with stable field and method ordering. */
  fun toJson(): String {
    fun quote(value: String): String = buildString {
      append('"')
      for (c in value) when (c) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        else -> if (c.code < 32) append("\\u%04x".format(c.code)) else append(c)
      }
      append('"')
    }
    fun strings(values: List<String>): String = values.joinToString(",", "[", "]", transform = ::quote)
    return "{\"schemaVersion\":$schemaVersion,\"classesAnalyzed\":$classesAnalyzed,\"methodsAnalyzed\":$methodsAnalyzed," +
      "\"rejectionSummary\":${rejectionSummary.entries.joinToString(",", "{", "}") { "${quote(it.key.name)}:${it.value}" }}," +
      "\"total\":$total,\"selected\":$selected,\"unselected\":$unselected,\"eligible\":$eligible,\"rejected\":$rejected," +
      "\"excludedByPolicy\":$excludedByPolicy," +
      "\"instructionCount\":$instructionCount,\"fieldCount\":$fieldCount,\"invokeCount\":$invokeCount," +
      "\"fields\":${strings(fields)},\"invokes\":${strings(invokes)},\"references\":${strings(references)},\"types\":${strings(types)}," +
      "\"opcodes\":${opcodes.entries.joinToString(",", "{", "}") { entry -> "${quote(entry.key.toString())}:${entry.value}" }}," +
      "\"estimatedInstructionCount\":$estimatedInstructionCount,\"bridgeCount\":$bridgeCount,\"methods\":" +
      methods.joinToString(",", "[", "]}") {
        val structured = it.methodIdentity?.let { id ->
          "{\"owner\":${quote(id.owner)},\"name\":${quote(id.name)},\"descriptor\":${quote(id.descriptor)}}"
        } ?: "null"
        val details = it.rejections.joinToString(",", "[", "]") { rejection ->
          "{\"reason\":${quote(rejection.reason.name)},\"code\":${quote(rejection.code.name)},\"detail\":${quote(rejection.detail)}," +
            "\"instructionIndex\":${rejection.instructionIndex},\"opcode\":${rejection.opcode}}"
        }
        "{\"identity\":${quote(it.identity)},\"methodIdentity\":$structured,\"selected\":${it.selected},\"eligible\":${it.eligible}," +
          "\"exclusionReason\":${it.exclusionReason?.let(::quote)}," +
          "\"owner\":${it.owner?.let(::quote)},\"name\":${it.name?.let(::quote)},\"descriptor\":${it.descriptor?.let(::quote)}," +
          "\"isStatic\":${it.isStatic},\"argumentTypes\":${strings(it.argumentTypes)},\"returnType\":${quote(it.returnType)}," +
          "\"hasTryCatch\":${it.hasTryCatch},\"hasInvokeDynamic\":${it.hasInvokeDynamic}," +
          "\"instructionCount\":${it.instructionCount},\"fieldCount\":${it.fieldCount},\"invokeCount\":${it.invokeCount}," +
          "\"reasons\":${strings(it.reasons)},\"rejections\":$details,\"fields\":${strings(it.fields)}," +
          "\"invokes\":${strings(it.invokes)},\"references\":${strings(it.references)},\"types\":${strings(it.types)}," +
          "\"opcodes\":${it.opcodes.toSortedMap().entries.joinToString(",", "{", "}") { entry -> "${quote(entry.key.toString())}:${entry.value}" }}," +
          "\"estimatedInstructionCount\":${it.estimatedInstructionCount},\"bridgeCount\":${it.bridgeCount}}"
      }
  }

  fun write(path: Path) {
    path.parent?.let(Files::createDirectories)
    path.writeText(toJson())
  }
}

/**
 * Inspects selection, policy exclusions, and opcode eligibility without mutating class nodes.
 *
 * Selectors and exclusions are full-match regexes against original `owner.method(descriptor)`
 * identities. [exactExclusions] carries platform performance-policy reasons.
 */
class VirtualizationAnalyzer(patterns: List<String>, exclusions: List<String> = emptyList(),
  private val exactExclusions: Map<String, String> = emptyMap()) {
  private enum class AnnotationDirective { VIRTUALIZE, HOT_PATH }

  private val selectors = patterns.map(::Regex)
  private val exclusions = exclusions.map(::Regex)

  fun analyze(classes: Collection<ClassNode>): VirtualizationAnalysisReport = VirtualizationAnalysisReport(
    classes.sortedBy { it.name }.flatMap { owner ->
      val classDirective = annotationDirective(
        owner.visibleAnnotations.orEmpty() + owner.invisibleAnnotations.orEmpty(), owner.name)
      owner.methods.sortedWith(compareBy<MethodNode> { it.name }.thenBy { it.desc }).map { method ->
        val identity = owner.name + "." + method.name + method.desc
        val methodDirective = annotationDirective(
          method.visibleAnnotations.orEmpty() + method.invisibleAnnotations.orEmpty(), identity)
        val patternSelected = selectors.any { it.matches(identity) }
        val annotationSelected = methodDirective == AnnotationDirective.VIRTUALIZE ||
          methodDirective == null && classDirective == AnnotationDirective.VIRTUALIZE
        val candidate = patternSelected || annotationSelected
        val exclusionReason = when {
          ObfuscationAnnotationPolicy.noObfuscation(method) -> "@NoObfuscation on method"
          ObfuscationAnnotationPolicy.renameOnly(method) -> "@RenameOnly on method"
          methodDirective == AnnotationDirective.HOT_PATH -> "@HotPath on method"
          candidate && exclusions.any { it.matches(identity) } -> "user virtualization exclusion"
          methodDirective == AnnotationDirective.VIRTUALIZE -> null
          ObfuscationAnnotationPolicy.noObfuscation(owner) -> "@NoObfuscation on class"
          ObfuscationAnnotationPolicy.renameOnly(owner) -> "@RenameOnly on class"
          classDirective == AnnotationDirective.HOT_PATH -> "@HotPath on class"
          classDirective == AnnotationDirective.VIRTUALIZE -> null
          patternSelected -> exactExclusions[identity]
          else -> null
        }
        val selected = when {
          exclusionReason != null -> false
          methodDirective == AnnotationDirective.VIRTUALIZE -> true
          classDirective == AnnotationDirective.VIRTUALIZE -> true
          else -> patternSelected
        }
        analyze(owner, method, selected, exclusionReason)
      }
    }, classes.size
  )

  private fun annotationDirective(annotations: List<AnnotationNode>, target: String): AnnotationDirective? {
    val virtualize = annotations.any { it.desc == ObfuscationAnnotationPolicy.VIRTUALIZE }
    val hotPath = annotations.any { it.desc == ObfuscationAnnotationPolicy.HOT_PATH }
    require(!(virtualize && hotPath)) {
      "Conflicting virtualization annotations on $target: @Virtualize and @HotPath"
    }
    return when {
      virtualize -> AnnotationDirective.VIRTUALIZE
      hotPath -> AnnotationDirective.HOT_PATH
      else -> null
    }
  }

  fun analyze(owner: ClassNode, method: MethodNode, selected: Boolean = true,
    exclusionReason: String? = null): VirtualizationMethodAnalysis {
    val rejections = mutableListOf<VirtualizationRejection>()
    fun reject(reason: VirtualizationRejectionReason, detail: String, index: Int? = null, opcode: Int? = null,
      code: VirtualizationRejectionCode? = null) {
      val rejection = VirtualizationRejection(reason, detail, index, opcode)
      rejections += if (code == null) rejection else rejection.copy(code = code)
    }
    val types = sortedSetOf<String>()
    fun checkType(type: Type, location: String, index: Int? = null, opcode: Int? = null, allowVoid: Boolean = false) {
      types += type.descriptor
      if (!(type.sort in Type.BOOLEAN..Type.DOUBLE || type.sort in setOf(Type.OBJECT, Type.ARRAY) ||
          allowVoid && type.sort == Type.VOID))
        reject(VirtualizationRejectionReason.UNSUPPORTED_TYPE, "unsupported type ${type.descriptor} in $location", index, opcode)
    }
    if (method.name.startsWith("<")) reject(VirtualizationRejectionReason.INITIALIZER,
      "constructors and class initializers are unsupported", code = if (method.name == "<clinit>")
        VirtualizationRejectionCode.CLASS_INITIALIZER else VirtualizationRejectionCode.CONSTRUCTOR)
    if (method.access and Opcodes.ACC_NATIVE != 0) reject(VirtualizationRejectionReason.NATIVE_METHOD, "native methods are unsupported")
    if (method.access and Opcodes.ACC_ABSTRACT != 0) reject(VirtualizationRejectionReason.ABSTRACT_METHOD, "abstract methods are unsupported")
    if (method.access and Opcodes.ACC_SYNCHRONIZED != 0) reject(VirtualizationRejectionReason.SYNCHRONIZED_METHOD, "synchronized methods are unsupported")
    checkType(Type.getReturnType(method.desc), "result", allowVoid = true)
    Type.getArgumentTypes(method.desc).forEachIndexed { index, type -> checkType(type, "argument $index") }
    val instructions = method.instructions.toArray()
    if (instructions.none { it.opcode >= 0 }) reject(VirtualizationRejectionReason.EMPTY_BODY, "empty method body")
    val supported = setOf(0, 16, 17, 18, 21, 54, 87, 89, 95, 96, 100, 104, 108, 112, 116,
      120, 122, 124, 126, 128, 130, 132, 145, 146, 147, 153, 154, 155, 156, 157, 158,
      159, 160, 161, 162, 163, 164, 167, 172, Opcodes.ACONST_NULL, Opcodes.ALOAD, Opcodes.ASTORE,
      Opcodes.ARETURN, Opcodes.RETURN, Opcodes.IF_ACMPEQ, Opcodes.IF_ACMPNE, Opcodes.IFNULL,
      Opcodes.IFNONNULL, Opcodes.GETSTATIC, Opcodes.PUTSTATIC, Opcodes.GETFIELD, Opcodes.PUTFIELD,
      Opcodes.INVOKESTATIC, Opcodes.INVOKEVIRTUAL, Opcodes.INVOKEINTERFACE, Opcodes.INVOKESPECIAL,
      Opcodes.INVOKEDYNAMIC, Opcodes.TABLESWITCH, Opcodes.LOOKUPSWITCH,
      Opcodes.CHECKCAST, Opcodes.INSTANCEOF,
      Opcodes.DCONST_0, Opcodes.DCONST_1, Opcodes.DLOAD, Opcodes.DSTORE, Opcodes.POP2, Opcodes.DUP2,
      Opcodes.DADD, Opcodes.DSUB, Opcodes.DMUL, Opcodes.DDIV, Opcodes.DREM, Opcodes.DNEG,
      Opcodes.DCMPL, Opcodes.DCMPG, Opcodes.I2D, Opcodes.D2I, Opcodes.DRETURN) +
      setOf(Opcodes.LCONST_0, Opcodes.LCONST_1, Opcodes.LLOAD, Opcodes.LSTORE,
        Opcodes.LADD, Opcodes.LSUB, Opcodes.LMUL, Opcodes.LDIV, Opcodes.LREM, Opcodes.LNEG,
        Opcodes.LSHL, Opcodes.LSHR, Opcodes.LUSHR, Opcodes.LAND, Opcodes.LOR, Opcodes.LXOR, Opcodes.LCMP,
        Opcodes.I2L, Opcodes.L2I, Opcodes.L2D, Opcodes.D2L, Opcodes.LRETURN,
        Opcodes.NEW, Opcodes.NEWARRAY, Opcodes.ANEWARRAY, Opcodes.ARRAYLENGTH,
        Opcodes.IALOAD, Opcodes.LALOAD, Opcodes.DALOAD, Opcodes.AALOAD, Opcodes.BALOAD, Opcodes.CALOAD, Opcodes.SALOAD,
        Opcodes.IASTORE, Opcodes.LASTORE, Opcodes.DASTORE, Opcodes.AASTORE, Opcodes.BASTORE, Opcodes.CASTORE, Opcodes.SASTORE) +
      setOf(Opcodes.ATHROW, Opcodes.FCONST_0, Opcodes.FCONST_1, Opcodes.FCONST_2, Opcodes.FLOAD, Opcodes.FSTORE,
        Opcodes.FADD, Opcodes.FSUB, Opcodes.FMUL, Opcodes.FDIV, Opcodes.FREM, Opcodes.FNEG,
        Opcodes.FCMPL, Opcodes.FCMPG, Opcodes.I2F, Opcodes.F2I, Opcodes.L2F, Opcodes.F2L,
        Opcodes.F2D, Opcodes.D2F, Opcodes.FRETURN, Opcodes.FALOAD, Opcodes.FASTORE) +
      (Opcodes.ICONST_M1..Opcodes.ICONST_5)
    val fields = sortedSetOf<String>()
    val invokes = sortedSetOf<String>()
    val references = sortedSetOf<String>()
    val bridges = sortedSetOf<String>()
    method.tryCatchBlocks.mapNotNull { it.type }.distinct().forEach { type ->
      references += type
      types += "L$type;"
      bridges += "${Opcodes.INSTANCEOF}:$type"
    }
    val canonicalConstructors = mutableSetOf<MethodInsnNode>()
    val branchTargets = instructions.filterIsInstance<JumpInsnNode>().map { it.label }.toSet() +
      instructions.filterIsInstance<TableSwitchInsnNode>().flatMap { it.labels + it.dflt } +
      instructions.filterIsInstance<LookupSwitchInsnNode>().flatMap { it.labels + it.dflt }
    instructions.forEachIndexed { newIndex, candidate ->
      if (candidate !is TypeInsnNode || candidate.opcode != Opcodes.NEW) return@forEachIndexed
      val dupIndex = (newIndex + 1 until instructions.size).firstOrNull { instructions[it].opcode >= 0 } ?: return@forEachIndexed
      if (instructions[dupIndex].opcode != Opcodes.DUP) return@forEachIndexed
      var nested = 0
      for (index in dupIndex + 1 until instructions.size) {
        val current = instructions[index]
        if (current is LabelNode && current in branchTargets ||
          current is JumpInsnNode || current is TableSwitchInsnNode || current is LookupSwitchInsnNode ||
          current.opcode in setOf(Opcodes.ATHROW, Opcodes.RETURN, Opcodes.IRETURN, Opcodes.LRETURN,
            Opcodes.FRETURN, Opcodes.DRETURN, Opcodes.ARETURN) ||
          current is VarInsnNode && current.opcode == Opcodes.ASTORE) break
        if (current is TypeInsnNode && current.opcode == Opcodes.NEW) {
          val nestedDup = (index + 1 until instructions.size).firstOrNull { instructions[it].opcode >= 0 }
          if (nestedDup == null || instructions[nestedDup].opcode != Opcodes.DUP) break
          nested++
          continue
        }
        if (current is MethodInsnNode && current.name == "<init>") {
          if (nested > 0) { nested--; continue }
          if (current.opcode == Opcodes.INVOKESPECIAL && current.owner == candidate.desc) canonicalConstructors += current
          break
        }
      }
    }
    instructions.forEachIndexed { index, insn ->
      if (insn.opcode >= 0 && insn.opcode !in supported)
        reject(VirtualizationRejectionReason.UNSUPPORTED_OPCODE, "unsupported opcode ${insn.opcode} at instruction $index", index, insn.opcode,
          when (insn.opcode) {
            Opcodes.MONITORENTER, Opcodes.MONITOREXIT -> VirtualizationRejectionCode.MONITOR
            Opcodes.INVOKEDYNAMIC -> VirtualizationRejectionCode.INVOKEDYNAMIC
            Opcodes.JSR, Opcodes.RET -> VirtualizationRejectionCode.LEGACY_SUBROUTINE
            else -> VirtualizationRejectionCode.UNSUPPORTED_OPCODE
          })
      if (insn is LdcInsnNode && insn.cst !is Int && insn.cst !is Long && insn.cst !is Float &&
        insn.cst !is Double && insn.cst !is String &&
        !((insn.cst as? Type)?.sort in setOf(Type.OBJECT, Type.ARRAY)))
        reject(VirtualizationRejectionReason.UNSUPPORTED_CONSTANT, "unsupported constant at instruction $index", index, insn.opcode)
      if (insn is LdcInsnNode && insn.cst is Type && (insn.cst as Type).sort in setOf(Type.OBJECT, Type.ARRAY)) {
        val type = insn.cst as Type
        bridges += "class:${type.descriptor}"
        types += type.descriptor
      }
      if (insn is LdcInsnNode && insn.cst is String) {
        bridges += "string:${insn.cst}"
        types += "Ljava/lang/String;"
      }
      if (insn is JumpInsnNode && (insn.label !in instructions ||
          instructions.dropWhile { it !== insn.label }.none { it.opcode >= 0 }))
        reject(VirtualizationRejectionReason.INVALID_PROGRAM, "invalid branch target at instruction $index", index, insn.opcode,
          VirtualizationRejectionCode.INVALID_CONTROL_FLOW)
      when (insn) {
        is FieldInsnNode -> {
          fields += "${insn.owner}.${insn.name}:${insn.desc}"
          references += insn.owner
          bridges += "${insn.opcode}:${insn.owner}.${insn.name}:${insn.desc}"
          checkType(Type.getType(insn.desc), "field ${insn.owner}.${insn.name}", index, insn.opcode)
        }
        is MethodInsnNode -> {
          invokes += "${insn.owner}.${insn.name}${insn.desc}"
          references += insn.owner
          bridges += "${insn.opcode}:${insn.owner}.${insn.name}${insn.desc}"
          if (insn.name.startsWith("<") && insn !in canonicalConstructors) reject(VirtualizationRejectionReason.CONSTRUCTOR_INVOCATION,
            "constructor invocation at instruction $index", index, insn.opcode)
          checkType(Type.getReturnType(insn.desc), "invoke result", index, insn.opcode, true)
          Type.getArgumentTypes(insn.desc).forEach { checkType(it, "invoke argument", index, insn.opcode) }
        }
        is InvokeDynamicInsnNode -> {
          invokes += "${insn.name}${insn.desc}"
          references += insn.bsm.owner
          bridges += "${insn.opcode}:${insn.name}${insn.desc}:${insn.bsm.owner}.${insn.bsm.name}${insn.bsm.desc}"
          checkType(Type.getReturnType(insn.desc), "invokedynamic result", index, insn.opcode, true)
          Type.getArgumentTypes(insn.desc).forEach { checkType(it, "invokedynamic argument", index, insn.opcode) }
        }
        is TypeInsnNode -> {
          references += insn.desc
          checkType(if (insn.desc.startsWith("[")) Type.getType(insn.desc) else Type.getObjectType(insn.desc),
            "type instruction", index, insn.opcode)
          if (insn.opcode in setOf(Opcodes.CHECKCAST, Opcodes.INSTANCEOF)) bridges += "${insn.opcode}:${insn.desc}"
        }
      }
    }
    types.forEach { descriptor ->
      val type = Type.getType(descriptor)
      if (type.sort == Type.OBJECT) references += type.internalName
      if (type.sort == Type.ARRAY) references += type.descriptor
    }
    if (rejections.isEmpty()) {
      try { JvmToVirtualCompiler.compile(owner, method) }
      catch (error: IllegalArgumentException) {
        val detail = error.message ?: "invalid virtual program"
        val code = when {
          detail.contains("control flow") || detail.contains("branch target") -> VirtualizationRejectionCode.INVALID_CONTROL_FLOW
          detail.contains("stack") || detail.contains("kind") || detail.contains("local") -> VirtualizationRejectionCode.INVALID_STACK
          else -> VirtualizationRejectionCode.INVALID_PROGRAM
        }
        reject(VirtualizationRejectionReason.INVALID_PROGRAM, detail, code = code)
      }
    }
    val identity = MethodIdentity(owner.name, method.name, method.desc)
    return VirtualizationMethodAnalysis(identity.toString(), selected, exclusionReason, rejections.map { it.detail }, identity,
      rejections.toList(), fields.toList(), invokes.toList(), references.toList(),
      instructions.filter { it.opcode >= 0 }.groupingBy { it.opcode }.eachCount().toSortedMap(),
      types.toList(), instructions.count { it.opcode >= 0 }, bridges.size,
      method.access and Opcodes.ACC_STATIC != 0, Type.getArgumentTypes(method.desc).map { it.descriptor },
      Type.getReturnType(method.desc).descriptor, method.tryCatchBlocks.isNotEmpty(),
      instructions.any { it is InvokeDynamicInsnNode })
  }
}
