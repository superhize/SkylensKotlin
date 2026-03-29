package org.nextrg.skylens.helpers.config

import net.fabricmc.loader.api.FabricLoader
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

open class DataHolder<T>(
    private val serializer: DataSerializer<T>,
    fileName: String,
    private val defaultFactory: () -> T,
    subDirectory: String = ""
) {
    private val lock = Any()
    private val filePath: Path = resolvePath(fileName, subDirectory)

    var data: T = defaultFactory()
        private set

    init {
        reload()
    }

    fun reload() {
        synchronized(lock) {
            ensureParentDirectory()

            if (Files.notExists(filePath)) {
                data = defaultFactory()
                writeAtomically()
                return
            }

            val loaded = runCatching {
                Files.newBufferedReader(filePath, StandardCharsets.UTF_8).use { reader ->
                    serializer.fromJson(reader.readText())
                }
            }

            if (loaded.isSuccess) {
                data = loaded.getOrThrow()
            } else {
                backupCorruptFile()
                data = defaultFactory()
                writeAtomically()
            }
        }
    }

    fun save() {
        synchronized(lock) {
            writeAtomically()
        }
    }

    fun reset() {
        synchronized(lock) {
            data = defaultFactory()
        }
    }

    fun mutateAndSave(mutator: (T) -> Unit) {
        synchronized(lock) {
            mutator(data)
            writeAtomically()
        }
    }

    private fun resolvePath(fileName: String, subDirectory: String): Path {
        val baseDir = FabricLoader.getInstance().configDir
        return if (subDirectory.isBlank()) {
            baseDir.resolve(fileName)
        } else {
            baseDir.resolve(subDirectory).resolve(fileName)
        }
    }

    private fun ensureParentDirectory() {
        val parent = filePath.parent ?: return
        if (Files.notExists(parent)) {
            Files.createDirectories(parent)
        }
    }

    private fun writeAtomically() {
        ensureParentDirectory()
        val tmp = filePath.resolveSibling("${filePath.fileName}.tmp")

        Files.newBufferedWriter(tmp, StandardCharsets.UTF_8).use { writer ->
            writer.write(serializer.toJson(data))
        }

        try {
            Files.move(tmp, filePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp, filePath, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun backupCorruptFile() {
        if (Files.notExists(filePath)) return

        val backup = filePath.resolveSibling("${filePath.fileName}.corrupt-${System.currentTimeMillis()}")
        runCatching {
            Files.copy(filePath, backup, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}