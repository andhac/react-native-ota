/**
 * Shared protocol types for react-native-ota.
 *
 * These are placeholders for MS0. Runtime validation and fixtures land later
 * (see docs/ARCHITECTURE.md §9 and docs/ROADMAP.md MS4+).
 */

/** Signed update manifest (v1) — fields filled in later milestones. */
export interface ManifestV1 {
  /** Placeholder — schema defined in later milestones. */
  readonly _placeholder?: true;
}

/** Device check request query parameters (Device API). */
export interface DeviceUpdateRequest {
  /** Placeholder — schema defined in later milestones. */
  readonly _placeholder?: true;
}

/**
 * Device check response.
 * Future shapes include action: "update" | "none" | "rollback".
 */
export interface DeviceUpdateResponse {
  /** Placeholder — schema defined in later milestones. */
  readonly _placeholder?: true;
}

/** Metadata about the release currently running on a device. */
export interface ReleaseInfo {
  /** Placeholder — schema defined in later milestones. */
  readonly _placeholder?: true;
}
