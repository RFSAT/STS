package com.rfsat.sts.log
object Logger {
    var verbose = false
    fun i(tag: String, msg: String) { if (verbose) println("I/$tag: $msg") }
    fun w(tag: String, msg: String) { if (verbose) println("W/$tag: $msg") }
    fun e(tag: String, msg: String) { if (verbose) println("E/$tag: $msg") }
}
