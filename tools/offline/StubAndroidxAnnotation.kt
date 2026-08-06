package androidx.annotation

import kotlin.reflect.KClass

/** Media3 marks its RTSP source unstable, and the compiler will not let a
 *  caller touch it without this. The stub carries the real shape so a wrong
 *  usage fails here rather than in CI. */
@Retention(AnnotationRetention.BINARY)
annotation class OptIn(vararg val markerClass: KClass<out Annotation>)
