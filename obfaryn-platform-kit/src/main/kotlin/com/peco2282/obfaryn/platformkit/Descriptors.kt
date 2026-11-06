package com.peco2282.obfaryn.platformkit

import com.peco2282.obfaryn.api.transformer.MemberKey
import org.objectweb.asm.Type
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * JVM type descriptor for this class (e.g. `Ljava/lang/String;`, `I`, `[Ljava/lang/Object;`),
 * computed from the real `Class` object instead of hand-written. Only usable when the class is
 * actually loadable on the current classpath — a target framework's type that a platform
 * provider deliberately does not depend on (to avoid pulling in that framework's jar) still needs
 * the string-based [isAnnotatedWith] and [isSubtypeOf] overloads instead.
 */
val Class<*>.descriptor: String get() = Type.getDescriptor(this)

/** JVM internal name for this class (e.g. `java/lang/String`), computed instead of hand-written. */
val Class<*>.internalName: String get() = Type.getInternalName(this)

/**
 * JVM method descriptor matching this reflected method's actual parameter and return types
 * (e.g. `(Ljava/lang/String;I)V`), instead of hand-written.
 */
val Method.descriptor: String get() = Type.getMethodDescriptor(this)

/** JVM method descriptor matching this reflected constructor's actual parameter types. */
val Constructor<*>.descriptor: String get() = Type.getConstructorDescriptor(this)

/**
 * This method's identity as a [MemberKey] (owner, name, descriptor), instead of writing all three
 * by hand. Only usable when the declaring class is actually on the current classpath; see
 * [isAnnotatedWith] and [isSubtypeOf] for matching a target framework's own methods by name alone.
 */
val Method.memberKey: MemberKey get() = MemberKey(declaringClass.internalName, name, descriptor)

/** This constructor's identity as a [MemberKey], using the JVM's own name (`<init>`) for it. */
val Constructor<*>.memberKey: MemberKey get() = MemberKey(declaringClass.internalName, "<init>", descriptor)

/** This field's identity as a [MemberKey] (owner, name, its type's descriptor). */
val Field.memberKey: MemberKey get() = MemberKey(declaringClass.internalName, name, type.descriptor)
