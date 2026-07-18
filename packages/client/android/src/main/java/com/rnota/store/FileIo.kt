package com.rnota.store

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Filesystem boundary for the store.
 * Production uses [DEFAULT]; unit tests inject crash / disk-full behavior.
 */
interface FileIo {
  fun writeAtomic(
    target: File,
    temporary: File,
    bytes: ByteArray,
  )

  fun readBytes(file: File): ByteArray

  fun exists(file: File): Boolean

  fun isFile(file: File): Boolean

  fun isDirectory(file: File): Boolean

  fun mkdir(dir: File): Boolean

  fun deleteRecursively(file: File): Boolean

  fun listNames(dir: File): List<String>

  fun rename(from: File, to: File): Boolean

  companion object {
    val DEFAULT: FileIo = RealFileIo()
  }
}

/**
 * Crash-injection points for atomic state writes (test harness).
 */
enum class AtomicWriteCrashPoint {
  NONE,
  AFTER_TEMP_WRITE,
  AFTER_TEMP_FSYNC,
  BEFORE_RENAME,
  AFTER_RENAME,
}

internal class RealFileIo : FileIo {
  override fun writeAtomic(
    target: File,
    temporary: File,
    bytes: ByteArray,
  ) {
    try {
      temporary.parentFile?.mkdirs()
      FileOutputStream(temporary).use { fos ->
        fos.write(bytes)
        fos.fd.sync()
      }
      atomicMove(temporary, target)
      syncParent(target)
    } catch (e: IOException) {
      if (isNoSpace(e)) {
        temporary.delete()
        throw BundleStoreException.DiskFull(e)
      }
      throw BundleStoreException.IoFailure("Failed to atomically write ${target.name}", e)
    }
  }

  override fun readBytes(file: File): ByteArray = file.readBytes()

  override fun exists(file: File): Boolean = file.exists()

  override fun isFile(file: File): Boolean = file.isFile

  override fun isDirectory(file: File): Boolean = file.isDirectory

  override fun mkdir(dir: File): Boolean = dir.mkdirs() || dir.isDirectory

  override fun deleteRecursively(file: File): Boolean = file.deleteRecursively()

  override fun listNames(dir: File): List<String> = dir.list()?.toList().orEmpty()

  override fun rename(
    from: File,
    to: File,
  ): Boolean = atomicMove(from, to)

  private fun atomicMove(
    from: File,
    to: File,
  ): Boolean {
    try {
      Files.move(
        from.toPath(),
        to.toPath(),
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE,
      )
      return true
    } catch (_: IOException) {
      // Some platforms reject ATOMIC_MOVE; fall back to replace.
      Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
      return true
    }
  }

  private fun syncParent(file: File) {
    val parent = file.parentFile ?: return
    try {
      FileOutputStream(parent).use { it.fd.sync() }
    } catch (_: Exception) {
      // Directory fsync is best-effort; not available on all Android versions.
    }
  }

  private fun isNoSpace(e: IOException): Boolean {
    val message = e.message?.lowercase().orEmpty()
    return message.contains("no space") ||
      message.contains("enospc") ||
      e.javaClass.simpleName.contains("ENOSPC", ignoreCase = true)
  }
}

/**
 * Test double that can simulate mid-write crashes and disk-full errors.
 */
class ControllableFileIo(
  private val delegate: FileIo = FileIo.DEFAULT,
  var crashPoint: AtomicWriteCrashPoint = AtomicWriteCrashPoint.NONE,
  var failWriteWithDiskFull: Boolean = false,
) : FileIo by delegate {
  override fun writeAtomic(
    target: File,
    temporary: File,
    bytes: ByteArray,
  ) {
    if (failWriteWithDiskFull) {
      throw BundleStoreException.DiskFull()
    }
    temporary.parentFile?.mkdirs()
    when (crashPoint) {
      AtomicWriteCrashPoint.NONE -> delegate.writeAtomic(target, temporary, bytes)
      AtomicWriteCrashPoint.AFTER_TEMP_WRITE -> {
        temporary.writeBytes(bytes)
        throw SimulatedCrashException("crash after temp write")
      }
      AtomicWriteCrashPoint.AFTER_TEMP_FSYNC -> {
        FileOutputStream(temporary).use { fos ->
          fos.write(bytes)
          fos.fd.sync()
        }
        throw SimulatedCrashException("crash after temp fsync")
      }
      AtomicWriteCrashPoint.BEFORE_RENAME -> {
        FileOutputStream(temporary).use { fos ->
          fos.write(bytes)
          fos.fd.sync()
        }
        throw SimulatedCrashException("crash before rename")
      }
      AtomicWriteCrashPoint.AFTER_RENAME -> {
        FileOutputStream(temporary).use { fos ->
          fos.write(bytes)
          fos.fd.sync()
        }
        delegate.rename(temporary, target)
        throw SimulatedCrashException("crash after rename")
      }
    }
  }
}

class SimulatedCrashException(
  message: String,
) : RuntimeException(message)
