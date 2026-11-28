@file:Suppress("unused")

package uy.kohesive.injekt.api

import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type

interface TypeReference<T> {
    val type: Type
}

abstract class FullTypeReference<T> : TypeReference<T> {
    override val type: Type =
        (javaClass.genericSuperclass as ParameterizedType).actualTypeArguments[0]
}

interface InjektFactory {
    fun getInstance(forType: Type): Any
}

interface InjektScope : InjektFactory
