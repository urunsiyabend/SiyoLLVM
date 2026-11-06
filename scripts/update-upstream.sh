#!/bin/sh
# Re-extracts the SiyoCompiler frontend snapshot.
#
#   scripts/update-upstream.sh <path-to-SiyoCompiler-checkout> <commit>
#
# Copies exactly the paths listed in upstream/siyocompiler/FILES, as they are
# at <commit>, into upstream/siyocompiler/, then rewrites MANIFEST.sha256 and
# COMMIT. The checkout is only read (git show); its working tree is ignored.
# See upstream/siyocompiler/PROVENANCE.md for the full procedure.
set -eu

if [ $# -ne 2 ]; then
    echo "usage: $0 <path-to-SiyoCompiler-checkout> <commit>" >&2
    exit 2
fi
checkout=$1
commit=$2

root=$(cd "$(dirname "$0")/.." && pwd)
dest="$root/upstream/siyocompiler"
list="$dest/FILES"

full=$(git -C "$checkout" rev-parse --verify "$commit^{commit}")

rm -rf "$dest/src"
grep -v '^#' "$list" | grep -v '^[[:space:]]*$' | while IFS= read -r path; do
    mkdir -p "$dest/$(dirname "$path")"
    git -C "$checkout" show "$full:$path" > "$dest/$path"
done

(
    cd "$dest"
    grep -v '^#' FILES | grep -v '^[[:space:]]*$' | LC_ALL=C sort | while IFS= read -r path; do
        sha256sum "$path"
    done
) > "$dest/MANIFEST.sha256"
echo "$full" > "$dest/COMMIT"
echo "snapshot updated to $full ($(wc -l < "$dest/MANIFEST.sha256") files)"
