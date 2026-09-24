# Changelog

All notable changes to Commit Spotlight are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.0.5] - 2026-09-24

### Added
- "Tint Editor Tabs for Highlighted Files" toggle (on by default) for anyone who'd rather keep
  tabs their normal color and rely on the in-editor highlight alone.

### Changed
- "Show Only Highlighted Commits in Git Log" and "Prioritize Newest Commit on Overlapping Lines"
  now keep the context menu open when toggled, matching the color and opacity pickers next to
  them, so you can flip a setting and see the result without reopening the menu.

## [1.0.4] - 2026-09-23

### Added
- Highlights are now automatically cleared when the current branch changes, or when a
  highlighted commit is no longer available (e.g. dropped or rewritten by a rebase), so they
  never linger and point at stale history.
- Each file's editor tab is now tinted to match its highlight color, kept in sync as you add,
  remove, recolor, or clear highlights.
- A highlight is now dropped automatically if a live edit touches its lines, instead of silently
  drifting to cover text the commit never wrote.

### Fixed
- Highlighting a commit that wasn't the most recent one to touch a file could paint completely
  unrelated lines: line positions were taken straight from that commit's own diff and painted
  onto the file as it exists now, with no adjustment for anything that changed the file's shape
  since — an insertion earlier in the file, a later edit to the same lines, other commits
  reshaping the surrounding code. Commit diffs are now remapped onto the file's current state by
  diffing the commit directly against it, so a line that's since been further changed is dropped
  (there's no longer a single honest "current" position for it) rather than shown in the wrong
  place, and everything else lands exactly where it now sits.
- The rounded highlight background for a block could extend one line past what the commit
  actually touched: the previous release's line-drift fix resolved a block's last line from an
  offset that (deliberately, for the line-break paint) pointed one character into the
  following line, instead of backing up to the line it was actually meant to resolve.

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

[Unreleased]: https://github.com/t-p-white/commit-spotlight/compare/v1.0.5...HEAD
[1.0.5]: https://github.com/t-p-white/commit-spotlight/compare/v1.0.4...v1.0.5
[1.0.4]: https://github.com/t-p-white/commit-spotlight/compare/v1.0.3...v1.0.4
[1.0.3]: https://github.com/t-p-white/commit-spotlight/compare/v1.0.2...v1.0.3
[1.0.2]: https://github.com/t-p-white/commit-spotlight/compare/v1.0.1...v1.0.2
[1.0.1]: https://github.com/t-p-white/commit-spotlight/compare/14b4e99...v1.0.1
