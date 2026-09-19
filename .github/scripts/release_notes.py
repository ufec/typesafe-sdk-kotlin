"""Extract a tagged release's title and notes from the changelog, or check it over.

Two modes.

With a tag, this is what the upstream JavaScript SDK's script of the same name
does: prove the changelog actually describes the release being published, and
hand the notes to `gh release create`. `publish.yml` calls it that way.

With `--check`, it validates the file's shape without referring to a tag. That is
what `check.yml` runs on every push, so a malformed entry is caught when it is
written rather than on release day.
"""

import re
import sys
from datetime import date
from pathlib import Path

CHANGELOG = Path("docs/changelog.md")
BUILD_SCRIPT = Path("build.gradle.kts")

# The tag has to match the version the build will publish, otherwise the artifact
# ends up labelled with the wrong number, which cannot be undone on JitPack.
VERSION_PATTERN = r"(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)"
HEADING_PATTERN = r"(?P<tag>v{version}) \((?P<date>\d{{4}}-\d{{2}}-\d{{2}})\)"


def sections(changelog: str) -> list[str]:
    """Split the file on level-two headings, dropping the "Unreleased" placeholder."""
    parts = re.split(r"^## ", changelog, flags=re.MULTILINE)[1:]
    return [part for part in parts if part.partition("\n")[0] != "Unreleased"]


def validate_structure(changelog: str) -> None:
    """Check every entry's heading and body, without reference to any particular tag."""
    entries = sections(changelog)
    if not entries:
        raise ValueError("The changelog has no release entries")

    pattern = re.compile(HEADING_PATTERN.format(version=VERSION_PATTERN))
    for entry in entries:
        title, _, body = entry.partition("\n")
        match = pattern.fullmatch(title)
        if match is None:
            raise ValueError(
                f"Invalid release heading: {title!r}. "
                "Expected 'vX.Y.Z (YYYY-MM-DD)' on a line of its own."
            )
        date.fromisoformat(match["date"])
        if not body.strip():
            raise ValueError(f"Empty release notes for {match['tag']}")


def release_notes(changelog: str, tag: str) -> tuple[str, str]:
    """Return the latest release heading and body, requiring a unique entry matching the tag."""
    entries = sections(changelog)
    matches = [entry for entry in entries if entry.partition("\n")[0].startswith(f"{tag} (")]
    if len(matches) != 1:
        raise ValueError(f"Expected exactly one changelog entry for {tag}, found {len(matches)}")
    if matches[0] != entries[0]:
        raise ValueError(f"Tag {tag} must match the latest changelog entry")
    title, _, body = matches[0].partition("\n")
    match = re.fullmatch(HEADING_PATTERN.format(version=VERSION_PATTERN), title)
    if match is None:
        raise ValueError(f"Invalid release heading: {title}")
    date.fromisoformat(match["date"])
    if not body.strip():
        raise ValueError(f"Empty release notes for {tag}")
    return title, body.strip() + "\n"


def build_version() -> str:
    """Read the version this build will publish, from the line that sets it."""
    match = re.search(
        r'^version\s*=\s*"([^"]+)"',
        BUILD_SCRIPT.read_text(),
        flags=re.MULTILINE,
    )
    if match is None:
        raise ValueError(f"Could not find a version assignment in {BUILD_SCRIPT}")
    return match[1]


def validate_release(changelog: str, tag: str, version: str) -> tuple[str, str]:
    """Keep tag/version validation shared by extraction and tagging."""
    if re.fullmatch(VERSION_PATTERN, version) is None or tag != f"v{version}":
        raise ValueError(
            f"Tag {tag} does not match the build version {version} or the vX.Y.Z format"
        )
    return release_notes(changelog, tag)


def main() -> None:
    try:
        run()
    except ValueError as error:
        # A traceback would bury the one line that says what is wrong.
        raise SystemExit(f"error: {error}") from None


def run() -> None:
    changelog = CHANGELOG.read_text()

    if sys.argv[1:] == ["--check"]:
        validate_structure(changelog)
        print(f"{CHANGELOG} is well formed")
        return

    if len(sys.argv) != 2:
        raise SystemExit("usage: release_notes.py <tag> | release_notes.py --check")

    tag = sys.argv[1]
    title, notes = validate_release(changelog, tag, build_version())
    Path("release-notes.md").write_text(notes)
    print(title)


if __name__ == "__main__":
    main()
