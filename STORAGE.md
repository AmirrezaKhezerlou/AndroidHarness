# Storage management (#37)

Settings → Storage uses the compact list design: Overview, Shared and Workspaces,
with a separate cleanup preview and final confirmation. Numbers come from local
files; prototype sample figures are not used.

## Sharing and accounting

The Linux toolchain already has one app-side prefix shared by local workspaces.
npm and pip now use app-managed download caches outside that prefix. These caches
are not copied into the privileged runtime's staging archive. The privileged
execution tier uses its own cache under its build-specific directory: Android's
UID and SELinux separation prevents it from entering the app's private cache.
Existing Gradle caches in the shared toolchain home are measured and retained.
Existing npm/pip caches are also retained; they are not silently migrated.

Project configuration, node_modules and virtual environments are not merged or
symlinked together. Project-local cache overrides still apply. These environment
variables do not sandbox arbitrary commands or override global package install
behavior; use project environments for separate installed dependency versions.

Totals are logical file bytes, not allocated disk space. Known roots, nested
workspaces, aliases and hard links are counted once when file identities are
available. Symlinks are not traversed. Scans are cancellable and bounded to
200,000 entries; partial totals are labeled. Picker-only folders, SSH hosts and
the privileged deployment/cache are not traversed or estimated.

## Cleanup policy

Nothing is deleted by a scan. The user reviews exact paths and byte counts, then
confirms removal. Eligible categories are:

- Interrupted tool downloads older than 24 hours in the managed download folder.
- Managed app npm/pip download caches whose entire reviewed tree is older than
  24 hours. Legacy and Gradle caches stay kept.
- Recognized Gradle `build/outputs`, `build/intermediates` and `build/tmp` trees
  older than seven days. These require explicit selection and are offered only
  for a root or immediate module with a Gradle build file and output markers.
  Users must only select folders they can regenerate. Nested saved workspaces
  inside an output tree protect that tree.
- Managed tool packages meeting the ownership and dependency checks below.

Cleanup holds run-start, background-process-start, terminal and package-install
maintenance gates. Active tasks, open terminals and tracked background processes
block cleanup. Unverified privileged processes are retained as busy references.
These gates cannot control commands launched by unrelated apps or Full access
shell commands; Full access does not become a filesystem security boundary.

Previews capture file identities, timestamps, sizes and hashes. Cleanup rejects a
changed tree, symlink component or inaccessible path, and deletes only reviewed
files (never recursive deletion of a live cache/build directory). New files are
preserved. Failures/skips are reported and the inventory is refreshed.

Source folders, models, chat databases, images, task recovery, checkpoints,
settings and runtime staging are not cleanup categories. Build output selection
is explicit because unusual projects may keep important files in output folders.

## Shared tool requirements and ownership

New managed installs persist dependency metadata and extracted file ownership in
private atomic metadata. `pkg_install` records the workspace path as a requirement;
manual installs keep a manual requirement. Reusing an installed tool records the
additional requirement without another download. Shared tool details show these
recorded requirements. Releasing one changes only that record; it does not edit
project files or immediately remove a shared installation.

A cleanup candidate must have no recorded requirements, no installed dependents,
no app-toolchain requirement, no overlapping owned files, and an unchanged,
complete file manifest. Any installed package with missing dependency metadata
blocks package cleanup conservatively. Older installs, packages with unverifiable
entries (including symlinks) and modified files remain protected. Sizes and actual
workspace use are not guessed from package names. Recorded requirements may not
cover commands started outside the managed installer; users must retain a
requirement for every workspace still using a tool.

Removal quarantines package-owned files before committing the installed marker.
A journal restores moved files after an interrupted/failed uncommitted removal;
a committed removal completes quarantine deletion on restart. Deployment state is
invalidated after removal. The older package sheet directs removal to this
reviewed flow. Whole-toolchain uninstall remains a separately confirmed global
action, with the same idle gates and serialized package mutation.

No automatic environment reset, legacy manifest backfill, phone-wide cleanup,
remote cleanup, hard-link deduplication or installed language-package merging is
performed.
