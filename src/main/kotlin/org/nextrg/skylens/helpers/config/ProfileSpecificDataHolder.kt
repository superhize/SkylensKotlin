package org.nextrg.skylens.helpers.config

import java.util.concurrent.ConcurrentHashMap

class ProfileSpecificDataHolder<T>(
    private val serializer: DataSerializer<T>,
    private val featureName: String,
    private val defaultFactory: () -> T,
    private val profileIdProvider: () -> String?,
    private val subDirectory: String = ""
) {
    private val holders = ConcurrentHashMap<String, DataHolder<T>>()

    val data: T
        get() = forCurrentProfile().data

    fun save() {
        forCurrentProfile().save()
    }

    fun reload() {
        forCurrentProfile().reload()
    }

    fun reset() {
        forCurrentProfile().reset()
    }

    fun mutateAndSave(mutator: (T) -> Unit) {
        forCurrentProfile().mutateAndSave(mutator)
    }

    fun dataFor(profileId: String): T {
        return holderFor(profileId).data
    }

    fun saveFor(profileId: String) {
        holderFor(profileId).save()
    }

    private fun forCurrentProfile(): DataHolder<T> {
        return holderFor(profileIdProvider().orEmpty())
    }

    private fun holderFor(rawProfileId: String): DataHolder<T> {
        val profileId = sanitizeProfileId(rawProfileId.ifBlank { "global" })
        val normalizedFileName = if (featureName.endsWith(".json")) featureName else "$featureName.json"
        val profileDirectory = if (subDirectory.isBlank()) profileId else "$subDirectory/$profileId"

        return holders.computeIfAbsent(profileId) {
            DataHolder(serializer, normalizedFileName, defaultFactory, profileDirectory)
        }
    }

    private fun sanitizeProfileId(value: String): String {
        val sanitized = value.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
        return sanitized.ifBlank { "global" }
    }
}