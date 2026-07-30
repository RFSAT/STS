package com.google.gson.reflect

abstract class TypeToken<T> {
    val type: java.lang.reflect.Type = Any::class.java
    companion object {
        @JvmStatic fun getParameterized(raw: java.lang.reflect.Type, vararg args: java.lang.reflect.Type): TypeToken<Any> =
            object : TypeToken<Any>() {}
    }
}
