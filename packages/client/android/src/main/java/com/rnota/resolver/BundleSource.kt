package com.rnota.resolver

/**
 * Where the resolved JavaScript bundle comes from.
 */
enum class BundleSource {
  /** Ship-with-binary asset / main.jsbundle — RN host uses its default path. */
  EMBEDDED,

  /** On-disk OTA slot (`bundle.hbc`). */
  OTA,
}
