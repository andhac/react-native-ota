package com.rnota.verifier

import java.io.File
import java.nio.file.Path

/**
 * Ensures verification targets stay inside an allowed directory (path traversal defense).
 */
internal object PathGuard {
  fun isUnderAllowedRoot(
    target: File,
    allowedRoot: File?,
  ): Boolean {
    if (allowedRoot == null) {
      return true
    }
    return try {
      val root = allowedRoot.canonicalFile.toPath().normalize()
      val resolved = target.canonicalFile.toPath().normalize()
      resolved.startsWith(root)
    } catch (_: Exception) {
      false
    }
  }

  fun containsTraversalSegment(path: String): Boolean {
    return path.split('/', '\\').any { segment ->
      segment == ".." || segment.contains("..")
    }
  }
}
