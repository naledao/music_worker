package com.openclaw.musicworker.desktop

import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

object DesktopFileLogger {
    private val initialized = AtomicBoolean(false)
    private val lock = Any()
    private val formatter: DateTimeFormatter = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        .withZone(ZoneId.systemDefault())

    fun installGlobalHandlers() {
        if (!initialized.compareAndSet(false, true)) {
            return
        }

        write("INFO", "desktop logger initialized path=${filePath()}")
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            error("uncaught exception on thread=${thread.name}", throwable)
        }
        Runtime.getRuntime().addShutdownHook(
            Thread {
                info("desktop app shutting down")
            },
        )
    }

    fun filePath(): Path = DesktopPaths.desktopLogFile()

    fun info(message: String) {
        write("INFO", message)
    }

    fun warn(message: String) {
        write("WARN", message)
    }

    fun error(message: String, throwable: Throwable? = null) {
        write("ERROR", message, throwable)
    }

    private fun write(level: String, message: String, throwable: Throwable? = null) {
        synchronized(lock) {
            val logFile = ensureLogFile()
            rotateIfNeeded(logFile)
            Files.writeString(
                logFile,
                buildEntry(level, message, throwable),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND,
            )
        }
    }

    private fun ensureLogFile(): Path {
        val logFile = filePath()
        logFile.parent?.createDirectories()
        if (!logFile.exists()) {
            Files.createFile(logFile)
        }
        return logFile
    }

    private fun rotateIfNeeded(logFile: Path) {
        if (!logFile.exists()) {
            return
        }
        if (Files.size(logFile) < MAX_LOG_BYTES) {
            return
        }

        val rotatedFile = logFile.resolveSibling("$LOG_FILE_NAME.1")
        Files.deleteIfExists(rotatedFile)
        Files.move(logFile, rotatedFile, StandardCopyOption.REPLACE_EXISTING)
        Files.createFile(logFile)
    }

    private fun buildEntry(level: String, message: String, throwable: Throwable?): String {
        return buildString {
            append(formatter.format(Instant.now()))
            append(" [")
            append(level)
            append("] ")
            append(message.trim())
            append('\n')
            if (throwable != null) {
                append(throwable.asStackTrace())
                if (!endsWith('\n')) {
                    append('\n')
                }
            }
        }
    }

    private fun Throwable.asStackTrace(): String {
        val buffer = StringWriter()
        PrintWriter(buffer).use { writer ->
            printStackTrace(writer)
        }
        return buffer.toString()
    }

    private const val LOG_FILE_NAME = "desktop.log"
    private const val MAX_LOG_BYTES = 2L * 1024L * 1024L
}
