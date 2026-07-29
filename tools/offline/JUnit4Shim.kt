// Mirrors the ACTUAL public overload set of org.junit.Assert in JUnit 4.13.
// Deliberately no convenience overloads: a shim more permissive than the real
// thing hides exactly the errors it exists to catch. An assertEquals(Int, Int,
// Int) must fail here for the same reason it fails in CI.
package org.junit

@Target(AnnotationTarget.FUNCTION) @Retention(AnnotationRetention.RUNTIME) annotation class Test
@Target(AnnotationTarget.FUNCTION) @Retention(AnnotationRetention.RUNTIME) annotation class Before
@Target(AnnotationTarget.FUNCTION) @Retention(AnnotationRetention.RUNTIME) annotation class After
@Target(AnnotationTarget.FUNCTION) @Retention(AnnotationRetention.RUNTIME) annotation class Ignore

object Assert {
    @JvmStatic fun assertEquals(expected: Any?, actual: Any?) {
        if (expected != actual) throw AssertionError("expected:<$expected> but was:<$actual>")
    }
    @JvmStatic fun assertEquals(message: String?, expected: Any?, actual: Any?) {
        if (expected != actual) throw AssertionError("$message expected:<$expected> but was:<$actual>")
    }
    @JvmStatic fun assertEquals(expected: Long, actual: Long) {
        if (expected != actual) throw AssertionError("expected:<$expected> but was:<$actual>")
    }
    @JvmStatic fun assertEquals(message: String?, expected: Long, actual: Long) {
        if (expected != actual) throw AssertionError("$message expected:<$expected> but was:<$actual>")
    }
    @JvmStatic fun assertEquals(expected: Double, actual: Double, delta: Double) {
        if (!(Math.abs(expected - actual) <= delta) && expected != actual)
            throw AssertionError("expected:<$expected> but was:<$actual> (delta $delta)")
    }
    @JvmStatic fun assertEquals(message: String?, expected: Double, actual: Double, delta: Double) {
        if (!(Math.abs(expected - actual) <= delta) && expected != actual)
            throw AssertionError("$message expected:<$expected> but was:<$actual> (delta $delta)")
    }
    @JvmStatic fun assertEquals(expected: Float, actual: Float, delta: Float) {
        if (!(Math.abs(expected - actual) <= delta) && expected != actual)
            throw AssertionError("expected:<$expected> but was:<$actual>")
    }
    @JvmStatic fun assertNotEquals(unexpected: Any?, actual: Any?) {
        if (unexpected == actual) throw AssertionError("values should differ: <$actual>")
    }
    @JvmStatic fun assertArrayEquals(expected: DoubleArray?, actual: DoubleArray?, delta: Double) {
        if (expected == null || actual == null || expected.size != actual.size) throw AssertionError("arrays differ")
        for (i in expected.indices) if (Math.abs(expected[i] - actual[i]) > delta) throw AssertionError("arrays differ at $i")
    }
    @JvmStatic fun assertTrue(condition: Boolean) { if (!condition) throw AssertionError("expected true") }
    @JvmStatic fun assertTrue(message: String?, condition: Boolean) { if (!condition) throw AssertionError(message) }
    @JvmStatic fun assertFalse(condition: Boolean) { if (condition) throw AssertionError("expected false") }
    @JvmStatic fun assertFalse(message: String?, condition: Boolean) { if (condition) throw AssertionError(message) }
    @JvmStatic fun assertNull(o: Any?) { if (o != null) throw AssertionError("expected null but was:<$o>") }
    @JvmStatic fun assertNull(message: String?, o: Any?) { if (o != null) throw AssertionError(message) }
    @JvmStatic fun assertNotNull(o: Any?) { if (o == null) throw AssertionError("expected non-null") }
    @JvmStatic fun assertNotNull(message: String?, o: Any?) { if (o == null) throw AssertionError(message) }
    @JvmStatic fun fail() : Nothing = throw AssertionError("failed")
    @JvmStatic fun fail(message: String?): Nothing = throw AssertionError(message)
}
