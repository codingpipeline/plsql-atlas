# Git and release workflow

Repository: <https://github.com/codingpipeline/plsql-atlas> (default branch `main`).
Commands are for Git Bash or any shell; on Windows PowerShell use `.\mvnw.cmd` instead of `./mvnw`.

## 1. First publish (already done once — kept for reference)

```bash
# create an EMPTY repo on GitHub first (no README / .gitignore / license), then in the project folder:
git remote add origin https://github.com/codingpipeline/plsql-atlas.git
git push -u origin main
```

Check it worked:

```bash
git remote -v
git ls-remote origin          # the commit id shown must equal `git rev-parse HEAD`
```

## 2. Everyday: save a change

```bash
git status                    # what changed
git diff                      # read it before committing
./mvnw -B verify              # tests + jar must pass first
git add -A                    # .gitignore keeps target/, corpus/, .agentdocs/ out
git commit -m "Short imperative summary"
git push
```

Commit message style used here: a short first line (`M7: ...`, `Fix ...`, `Licensing: ...`), a blank line, then bullets saying *what* and *why*.

## 3. Adding a new feature (safer than committing straight to main)

```bash
git checkout -b feature/short-name
# ... edit, test ...
git add -A && git commit -m "Add short-name"
git push -u origin feature/short-name      # then open a Pull Request on GitHub and merge it
# afterwards, locally:
git checkout main && git pull
git branch -d feature/short-name
```

Solo and in a hurry: commit on `main` and `git push` as in section 2.

## 4. Before every push: quick checklist

- `./mvnw -B verify` is green.
- Drop-in smoke test (this is exactly what a user does):
  ```bash
  mkdir /tmp/atlas-check && cp target/plsql-atlas.jar /tmp/atlas-check/
  printf 'create or replace procedure p is\nbegin\n  null;\nend;\n/\n' > /tmp/atlas-check/p.prc
  (cd /tmp/atlas-check && java -jar plsql-atlas.jar --no-ui)     # expect VERDICT: VERIFIED, exit code 0
  ```
- `git status` shows no PL/SQL samples, no `corpus/`, no `target/`, no `.agentdocs/`.
- README screenshots still match the UI (see section 7 if the UI changed).

## 5. Publish a release (the downloadable jar)

> A fully explained, step-by-step example with checks after every step is in [RELEASE-WALKTHROUGH.md](RELEASE-WALKTHROUGH.md).

The release workflow (`.github/workflows/release.yml`) runs when a tag starting with `v` is pushed. It builds, tests, smoke-tests the jar, and publishes `plsql-atlas.jar` plus a `SHA256SUMS` file on the Releases page.

1. Make sure CI on `main` is green (GitHub → Actions).
2. Set the new version in **three places** (they are not generated from one source yet):
   - `pom.xml` → `<version>`
   - `src/main/java/dev/sandeep/plsqlparser/emit/AgentDocs.java` → `VERSION`
   - `src/main/java/dev/sandeep/plsqlparser/cli/Main.java` → `version = {"plsql-atlas X.Y.Z", ...}`
3. Commit and push, then tag:
   ```bash
   git add -A && git commit -m "Release X.Y.Z" && git push
   git tag vX.Y.Z
   git push origin vX.Y.Z
   ```
4. Watch Actions → *Release*; the jar appears under Releases → *Latest*.

Tagged the wrong commit? Delete and redo (before anyone downloads it):

```bash
git tag -d vX.Y.Z
git push --delete origin vX.Y.Z      # also delete the draft/published release on GitHub
```

## 6. Undo helpers

```bash
git restore <file>                   # throw away uncommitted edits to one file
git restore --staged <file>          # unstage a file, keep the edit
git commit --amend -m "New message"  # fix the LAST commit message (only if not pushed yet)
git revert <commit-id>               # safe undo of a pushed commit (creates a new commit)
git log --oneline -10                # recent history
```

Avoid `git push --force` once other people have cloned the repo.

## 7. Refresh the README screenshots (after UI changes)

1. Copy a few small PL/SQL files plus `target/plsql-atlas.jar` into an empty folder (do not commit them).
2. `java -jar plsql-atlas.jar --no-open`, open <http://127.0.0.1:9000>.
3. Capture Verdict, Explore, Files, ER diagram and the finished live feed; save over `docs/img/*.jpg`.
4. `git add docs/img && git commit -m "Refresh screenshots" && git push`.

## 8. Things that must stay out of the repo

`target/` (build output), `corpus/` (third-party test code, downloaded by `scripts/fetch-real-world-corpus.*`), `.agentdocs/` (generated), IDE folders — all already in `.gitignore`. Do not commit anyone's real PL/SQL.

Commits are stamped with the author from `git config user.name` / `user.email`; both become public on push.
