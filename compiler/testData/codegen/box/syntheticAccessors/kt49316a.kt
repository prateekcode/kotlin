// TARGET_BACKEND: JVM
// IGNORE_BACKEND_FIR: JVM_IR
// FIR reports VAL_REASSIGNMENT even in unreachable code
// This test should become irrelevant after KT-35565 is fixed.

fun test(foo: Foo): String {
    return foo.s

    // VAL_REASSIGNMENT not reported in unreachable code.
    // Make sure there's no BE internal error here.
    foo.s = "oops"
}

// CHECK_BYTECODE_LISTING
// - there should be no synthetic accessor generated in 'Foo'
class Foo(val s: String)

fun box() = test(Foo("OK"))
