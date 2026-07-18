# @rnota/cli

The `ota` command-line tool for the entire release workflow (module **M13**).

## Why this package exists

CI and developers need a single entrypoint to bundle with the **app-local** `hermesc`, sign offline, publish to the management API, and run `doctor` with the same verifier semantics as devices.

## Future commands

`bundle` · `sign` · `publish` · `promote` · `rollout` · `rollback` · `doctor` · `keys`

## Status

MS0: Commander.js skeleton — every command prints `Not implemented.`
