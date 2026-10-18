#!/usr/bin/env sh
# Recreates the local gradle-x.y branches of this project (they are not in the official Gradle repository) from
# patches/branches.tsv and patches/gradle-x.y.patch, each as a worktree work/gradle-x.y of the repository ./gradle.
#
# usage: scripts/create-branches.sh [--no-worktree] [branch...]     (run from the project directory)
#   no branch: all the branches of patches/branches.tsv
#   --no-worktree: create the branches only (git branch + git am in a temporary worktree removed afterwards)
#
# For each branch: the base (tag or commit) must resolve to the commit recorded in branches.tsv, a new branch
# gradle-x.y is created there in a new worktree work/gradle-x.y, its patch (git format-patch of our commits) is applied
# with git am (authors and messages kept, the committer is the local git identity of ./gradle).
# A branch or worktree that already exists is left alone (skipped). Nothing of the official repository is changed.
set -eu

ROOT=$(pwd)
REPO="$ROOT/gradle"
LIST="$ROOT/patches/branches.tsv"
WORKTREE=yes

if [ "${1:-}" = "--no-worktree" ]; then
    WORKTREE=no
    shift
fi
if [ ! -d "$REPO/.git" ] || [ ! -f "$LIST" ]; then
    echo "run from the project directory (needs gradle/ and patches/branches.tsv)" >&2
    exit 2
fi

wanted() {
    name=$1
    shift
    [ $# -eq 0 ] && return 0  # no branch given: all
    for w in "$@"; do
        [ "$w" = "$name" ] && return 0
    done
    return 1
}

grep -v '^#' "$LIST" | while IFS="$(printf '\t')" read -r branch base commit patch; do
    [ -n "$branch" ] || continue
    wanted "$branch" "$@" || continue
    dir="$ROOT/work/$branch"
    if git -C "$REPO" show-ref --verify --quiet "refs/heads/$branch"; then
        echo "$branch: branch exists, skipped"
        continue
    fi
    if [ -e "$dir" ]; then
        echo "$branch: $dir exists, skipped"
        continue
    fi
    actual=$(git -C "$REPO" rev-parse --verify --quiet "$base^{commit}" || true)
    if [ "$actual" != "$commit" ]; then
        echo "$branch: base $base is ${actual:-unknown} in ./gradle, expected $commit (fetch the official repository?)" >&2
        exit 1
    fi
    if [ "$WORKTREE" = yes ]; then
        git -C "$REPO" worktree add --quiet -b "$branch" "$dir" "$commit"
        target=$dir
    else
        git -C "$REPO" branch "$branch" "$commit"
        target=$(mktemp -d "${TMPDIR:-/tmp}/create-branches-XXXXXX")
        rmdir "$target"
        git -C "$REPO" worktree add --quiet "$target" "$branch"
    fi
    if [ "$patch" != "-" ]; then
        git -C "$target" am --quiet --keep-cr --whitespace=nowarn "$ROOT/patches/$patch"
        echo "$branch: created from $base, $(git -C "$target" rev-list --count "$commit..HEAD") commit(s) applied"
    else
        echo "$branch: created at $base (no commit of its own)"
    fi
    if [ "$WORKTREE" = no ]; then
        git -C "$REPO" worktree remove "$target"
    fi
done
