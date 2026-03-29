package org.nextrg.skylens.helpers.config

import com.google.gson.reflect.TypeToken
import tech.thatgravyboat.skyblockapi.api.profile.profile.ProfileAPI

object DataHolders {
    inline fun <reified T> create(
        fileName: String,
        noinline defaultFactory: () -> T,
        subDirectory: String = ""
    ): DataHolder<T> {
        val normalizedFileName = if (fileName.endsWith(".json")) fileName else "$fileName.json"
        val serializer = GsonDataSerializer<T>(object : TypeToken<T>() {}.type)
        return DataHolder(serializer, normalizedFileName, defaultFactory, subDirectory)
    }

    inline fun <reified T> createFeature(
        featureName: String,
        noinline defaultFactory: () -> T
    ): DataHolder<T> {
        val normalizedFeatureName = if (featureName.endsWith(".json")) featureName else "$featureName.json"
        val serializer = GsonDataSerializer<T>(object : TypeToken<T>() {}.type)
        return DataHolder(serializer, normalizedFeatureName, defaultFactory, "features")
    }

    inline fun <reified T> createProfileSpecific(
        filePrefix: String,
        noinline defaultFactory: () -> T,
        noinline profileIdProvider: () -> String? = { ProfileAPI.profileId?.toString() },
        subDirectory: String = "skylens/storage/profiles"
    ): ProfileSpecificDataHolder<T> {
        val normalizedPrefix = filePrefix.removeSuffix(".json")
        val serializer = GsonDataSerializer<T>(object : TypeToken<T>() {}.type)
        return ProfileSpecificDataHolder(serializer, normalizedPrefix, defaultFactory, profileIdProvider, subDirectory)
    }
}