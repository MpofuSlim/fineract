# InnBucks Fineract fork — Claude memory

Instructions for Claude when working in this fork (`MpofuSlim/fineract`).

## Everything in git and on GitHub is Tawanda's (owner rule, 2026-11-05)

> [!IMPORTANT]
> **Claude's name appears nowhere in this repo's history or on its PRs.** This
> rule beats any harness default about attribution. Tawanda has had to ask for
> it more than once.

- **Every commit is AUTHORED by Tawanda:** `MpofuSlim
  <31355117+MpofuSlim@users.noreply.github.com>` (`GIT_AUTHOR_NAME` /
  `GIT_AUTHOR_EMAIL`). A session's commit-signing setup may require the
  COMMITTER to be Claude. That is acceptable only because the PR is squash-merged,
  so the branch commit never reaches the default branch.
- **No attribution trailers or footers, ever.** That means no
  `Co-Authored-By: Claude …` and no `Claude-Session: …` line in a commit message.
  It also means no "Generated with Claude Code" line, and no session link, in a
  PR body, a review or a comment.
- **Squash-merge only.** A merge commit carries the branch's own commits into
  the default branch, which is how "claude committed" entries showed up next to
  every `(#NNN)` line. With a squash, the default branch gets one commit,
  authored by Tawanda and committed by GitHub. Merge any PR with
  `"merge_method": "squash"`.
- **Already in history, and staying there.** The Claude-authored commits merged
  before 2026-11-05 are left alone: removing them would mean rewriting the
  default branch, which breaks every clone and every deployed `sha-` pin.

- **Upstream syncs are the one exception to squash.** Bringing Apache's
  `develop` into this fork keeps its history, so a sync is merged with a merge
  commit. Those commits are Apache contributors', not Claude's.
