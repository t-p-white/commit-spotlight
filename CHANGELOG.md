# Changelog

All notable changes to Commit Spotlight are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.0.3] - 2026-08-19

### Fixed
- Rounded highlight background blocks could drift away from the lines they cover after an
  edit shifted lines above or within them, since the renderer painted from line numbers
  captured when the highlight was first drawn instead of tracking the highlighter's live
  position.
- Recoloring or clearing highlights for a subset of a batch's commits could patch the wrong
  batch if another highlight/clear/recolor ran concurrently, since batches were matched by
  list position rather than a stable identity — this could leave the Git Log row for a commit
  out of sync with its editor highlight.

## [1.0.2] - 2026-08-16

### Changed
- Dropped usage of an internal (non-API) `PillWithBackgroundPresentation` class to avoid
  relying on unsupported platform internals.

## [1.0.1] - 2026-08-15

### Added
- "Was N line(s)" hover pill on modified lines, showing the original text before the change.
- "Prioritize Newest Commit on Overlapping Lines" control, letting you choose whether the
  chronologically newest commit or the most-recently-highlighted commit wins when two
  highlighted commits touch the same line.

## [1.0.0] - 2026-08-06

### Added
- Initial release: highlight the lines added, changed, or deleted by selected Git Log commits,
  directly in any open editor.
- Deleted-line markers with hoverable "N lines deleted" labels.
- Full-row highlighting of selected commits in the Git Log.
- 8 selectable highlight colors with adjustable opacity.
- "Show Only Highlighted Commits" Git Log filter.
- "Open All Files in Commit" action.
- Selective and full highlight clearing.

[Unreleased]: https://github.com/t-p-white/commit-spotlight/compare/v1.0.3...HEAD
[1.0.3]: https://github.com/t-p-white/commit-spotlight/compare/v1.0.2...v1.0.3
[1.0.2]: https://github.com/t-p-white/commit-spotlight/compare/v1.0.1...v1.0.2
