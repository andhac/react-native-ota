package com.rnota.verifier

import java.io.File

/**
 * Ensures verification targets stay inside a required allowed directory (path traversal defense).
 */
internal object PathGuard {
  fun isUnderAllowedRoot(
    target: File,
    allowedRoot: File,
  ): Boolean {
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
