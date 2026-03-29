package org.nextrg.skylens.helpers.config

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.lang.reflect.Type

interface DataSerializer<T> {
    fun fromJson(raw: String): T
    fun toJson(value: T): String
}

class GsonDataSerializer<T>(
    private val type: Type,
    private val gson: Gson = DEFAULT_GSON
) : DataSerializer<T> {
    override fun fromJson(raw: String): T {
        return gson.fromJson(raw, type)
    }

    override fun toJson(value: T): String {
        return gson.toJson(value, type)
    }

    companion object {
        val DEFAULT_GSON: Gson = GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create()
    }
}

inline fun <reified T> dataSerializer(
    gson: Gson = GsonDataSerializer.DEFAULT_GSON
): DataSerializer<T> {
    return GsonDataSerializer(object : TypeToken<T>() {}.type, gson)
}