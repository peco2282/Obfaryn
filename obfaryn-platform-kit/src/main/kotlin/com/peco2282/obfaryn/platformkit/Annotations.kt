package com.peco2282.obfaryn.platformkit

import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldNode
import org.objectweb.asm.tree.MethodNode

/** Converts a dotted or internal class name to a JVM annotation descriptor (`Lcom/example/Ann;`). */
private fun annotationDescriptorOf(annotationClassName: String) = "L${annotationClassName.toInternalName()};"

private fun List<AnnotationNode>?.has(descriptor: String) = orEmpty().any { it.desc == descriptor }

/**
 * True if any annotation on this method, visible or invisible at runtime, is [annotationClassName]
 * (dotted or internal form, e.g. `org.bukkit.event.EventHandler`). Retention is not distinguished;
 * `SOURCE`-retained annotations are absent from both lists because the compiler already dropped them.
 */
fun MethodNode.isAnnotatedWith(annotationClassName: String): Boolean {
  val descriptor = annotationDescriptorOf(annotationClassName)
  return visibleAnnotations.has(descriptor) || invisibleAnnotations.has(descriptor)
}

/** @see MethodNode.isAnnotatedWith */
fun ClassNode.isAnnotatedWith(annotationClassName: String): Boolean {
  val descriptor = annotationDescriptorOf(annotationClassName)
  return visibleAnnotations.has(descriptor) || invisibleAnnotations.has(descriptor)
}

/** @see MethodNode.isAnnotatedWith */
fun FieldNode.isAnnotatedWith(annotationClassName: String): Boolean {
  val descriptor = annotationDescriptorOf(annotationClassName)
  return visibleAnnotations.has(descriptor) || invisibleAnnotations.has(descriptor)
}

/**
 * True if any annotation on this method is [annotationClass], computed from the real class
 * instead of a hand-written descriptor string. Only usable when [annotationClass] is actually on
 * the current classpath; a target framework's annotation that a platform provider deliberately
 * does not depend on still needs the string-based [isAnnotatedWith] overload above.
 */
fun MethodNode.isAnnotatedWith(annotationClass: Class<out Annotation>): Boolean = isAnnotatedWith(annotationClass.name)

/** @see MethodNode.isAnnotatedWith */
fun ClassNode.isAnnotatedWith(annotationClass: Class<out Annotation>): Boolean = isAnnotatedWith(annotationClass.name)

/** @see MethodNode.isAnnotatedWith */
fun FieldNode.isAnnotatedWith(annotationClass: Class<out Annotation>): Boolean = isAnnotatedWith(annotationClass.name)

/** `method.isAnnotatedWith<MyAnnotation>()`; see the [Class]-based overload above. */
inline fun <reified T : Annotation> MethodNode.isAnnotatedWith(): Boolean = isAnnotatedWith(T::class.java)

/** @see MethodNode.isAnnotatedWith */
inline fun <reified T : Annotation> ClassNode.isAnnotatedWith(): Boolean = isAnnotatedWith(T::class.java)

/** @see MethodNode.isAnnotatedWith */
inline fun <reified T : Annotation> FieldNode.isAnnotatedWith(): Boolean = isAnnotatedWith(T::class.java)
