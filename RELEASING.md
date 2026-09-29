# Releasing

Light builds and signs community Tools from a public commit (see
[light-sdk/builder](https://github.com/lightphone/light-sdk/tree/main/builder)). So a Chess release is a
tagged commit on `main`, not an APK we build.

## Versioning

- `tool/lighttool.toml` carries both numbers; bump them together in the release PR.
  - `versionName`: semver `X.Y.Z`, no pre-release suffix (Light rejects them). `UiCopy.VERSION` shows
    it on the About screen, and `ToolMetadataTest` keeps the two equal.
  - `versionCode`: +1 every release, never reused, never lower. Light's build server rejects a
    `versionCode` that isn't greater than the last published one, and Android refuses to install a
    lower one over a higher one. 0.1.0 is `versionCode = 1`, 0.2.0 is `2`, 0.3.0 is `3`, 0.3.1 is `4`.
- The Tool id `com.yarosz.chess` is permanent from the first published build. Never change it.
- Tag the merged release commit `vX.Y.Z`. The GitHub Release carries notes only, no APK.
- Release notes users see are the ones entered in Light's portal: three lines, in the listing's voice,
  kept in `docs/release-notes/X.Y.Z.md`. GitHub Releases are for developers.
- A release that changes the Pack says so in its notes: finished Puzzles, the Player Rating and Missed
  carry over (F1).

## Why we never publish our own APKs

Local builds are signed with the SDK's shared development key, which is public. Anyone could sign an
"update" with it that Android would accept over ours. Only Light-signed builds are distributed.

Anyone who has run a dev-signed build needs to uninstall it before installing a Light-signed one,
because the signatures differ; uninstalling deletes the Player Rating, the history and Missed.

## Rollback

Light rejects a lower `versionCode`, so there is no "go back". A rollback is a new release, with a
higher `versionCode`, carrying the old code. The save file (`puzzles.json`) therefore tolerates a newer
`schemaVersion` than the code knows: it is read leniently, never crashes, and keeps its `.bak`.

## Checklist

Release PR (version bump, notes, and the `light-sdk` submodule at Light's newest SDK tag):

- [ ] `build` (GitHub Actions) and `signoff/emulator` green, as for every PR.
- [ ] The LP3 checks open in `LEDGER.md`, done under the phone lease.
- [ ] `scripts/release-check.sh scan`, with `RELEASE_SCAN_PRIVATE` naming a local file of private
      patterns (names, serials, hostnames), one extended regex per line. The file stays outside the
      repo. The scan covers every tracked file and every commit on every branch.
- [ ] `scripts/release-check.sh relay`: `tool/lighttool.toml` declares INTERNET (ADR 0004), so
      `RelayConfig.URL` must name the deployed Relay over HTTPS; it fails while the URL is empty (W8).
      The script doesn't reach the Relay: also check that `curl https://chess-relay.yarosz.com/health`
      answers `{"status":"ok",...}` (W11, relay/README.md "Deploying").
- [ ] `scripts/release-check.sh apk`: the unsigned, minified release, as Light builds it. It checks
      the package, `versionCode` and `versionName` with `aapt dump badging`, fails on any permission
      beyond the set Light's SDK merges in, and prints the APK's size.
- [ ] `scripts/release-check.sh run`: the minified release (dev-signed, emulator only) opens, solves a
      Puzzle and shows About with the Pack's dump date, and the Tool's uid shows no network traffic.
      R8 must not strip what kotlinx-serialization needs: the save file and the Pack manifest are read
      through it.
- [ ] `scripts/release-check.sh upgrade vX.Y.Z-1` (the previous release tag): that build with two
      solved Puzzles, then this one installed over it. The Player Rating, Missed and the rated history
      must be unchanged.
- [ ] `mise run light-build` on the committed release HEAD: Light's extractor on a clean clone, then an
      offline, unsigned, minified release against the pinned SDK. Uncommitted work is invisible to it.
- [ ] Release notes: three lines in `docs/release-notes/X.Y.Z.md`.

After merge and tag. These follow the plan Light announced in
[discussion #204](https://github.com/orgs/lightphone/discussions/204); the portal isn't live yet and
the steps may change. Under Light's AI policy, everything sent to Light comes from the maintainer
personally, never from an agent.

- [ ] The maintainer submits the tagged commit hash in Light's developer portal, with the three lines
      of release notes.
- [ ] When Light's signed build is ready: download it, check the package id, `versionName` and
      `versionCode` with `aapt dump badging <apk>`, install it on a Light Phone III, solve a Puzzle,
      open About, and only then share it.
- [ ] For v1: "Send for review" in the portal.
