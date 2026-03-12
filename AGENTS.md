# ThreadForge Release Notes For Agents

## Scope

These instructions apply when working in this repository on any release, publish, tag, changelog, or Maven Central task.

## Release Rules

- Do not use a real version tag as a dry run.
- GitHub Actions `release.yml` only creates the GitHub Release and injects changelog content.
- Maven Central publishing is done locally, not by GitHub Actions.
- Release order is fixed:
  1. Update versioned docs and release notes.
  2. Run `mvn -B -ntp clean verify`.
  3. Publish locally to Central.
  4. Only after Central succeeds, create and push the release tag.
  5. Verify the GitHub Release workflow created the release.

## Signing Key

- The known-good signing key used by the successful `v1.1.0` release is `DB468B1F4337F2C8D79F01CB33DEC676A561416D` (`33DEC676A561416D`).
- Do not switch signing keys during release work unless the user explicitly says the project is migrating keys.
- If there is any doubt, inspect the previous published `.asc` artifact and confirm which key signed the last successful release before running `deploy`.

## ThreadForge 1.1.x Release Checklist

- Update version references in:
  - `pom.xml`
  - `README.md`
  - `CHANGELOG.md`
  - `docs/releases.md`
  - `docs/api/Home.md`
  - `docs/api/README.md`
  - `docs/github-wiki/Home.md` if the wiki mirror is being refreshed
  - `docs/FEATURE.md` if it is being kept in sync
- Keep the changelog link visible near the top of `README.md`.
- For Central publishing, prefer:
  - `mvn -B -ntp -P release -Dgpg.keyname=DB468B1F4337F2C8D79F01CB33DEC676A561416D clean deploy`
- If a failed tag or release already exists, delete the remote tag/release before retrying.

## Failure Triage

- `Could not find a public key by the key fingerprint` usually means the wrong signing key was used, not that Maven or Sonatype credentials are broken.
- If Central fails, stop before re-tagging. Fix Central first, then create the GitHub release tag.
- If GitHub Release fails but Central already succeeded, do not republish Central. Retry only the tag/release side.
