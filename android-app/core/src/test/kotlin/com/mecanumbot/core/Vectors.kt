package com.mecanumbot.core

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** protocol/vectors.json, read in place (path from the vectors.path system property). */
object Vectors {
    val root: JsonObject by lazy {
        val path = System.getProperty("vectors.path") ?: error("vectors.path is not set")
        Json.parseToJsonElement(File(path).readText()).jsonObject
    }

    fun array(key: String): List<JsonObject> = root.getValue(key).jsonArray.map { it.jsonObject }

    fun crc8(): List<JsonObject> = root.obj("crc8").getValue("vectors").jsonArray.map { it.jsonObject }
}

fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int
fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.long
fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content
fun JsonObject.ints(key: String): List<Int> = getValue(key).jsonArray.map { it.jsonPrimitive.int }
fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject
