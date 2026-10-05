# Release walkthrough: how version 0.1.1 was made

This is a real example, step by step, of making a fix and publishing it as a new downloadable version.
Each step says **what to run**, **why**, and **how you check it worked without any special tool** (a browser is enough).
Run the commands in Git Bash from the project folder. The short command list is in [GIT-WORKFLOW.md](GIT-WORKFLOW.md).

Repo: <https://github.com/codingpipeline/plsql-atlas>

---

## The big picture

```
edit code  ->  test locally  ->  commit  ->  push  ->  CI runs on GitHub  ->  CI green?  ->  tag  ->  Release workflow  ->  jar on Releases page
```

Two automatic jobs run on GitHub (defined in `.github/workflows/`):

| Job | Starts when | What it does |
|---|---|---|
| **CI** (`ci.yml`) | every push to `main` | builds and tests on Linux **and** Windows, then smoke-tests the jar the way a user runs it |
| **Release** (`release.yml`) | you push a tag starting with `v` | builds, tests, smoke-tests, then publishes `plsql-atlas.jar` and `SHA256SUMS` on the Releases page |

Rule of thumb: **never tag until CI on the same commit is green**, because the Release job repeats the same build and would fail the same way.

---

## Step 1: make the change

For 0.1.1 the problem was that Windows consoles showed `—`, `→` and `…` as `?`. The fix was a small helper that turns those into plain ASCII for the console only.

Edit the code, then add or update tests. Write a test for the bug first if you can; here the test checks that everything printed to the console is ASCII.

## Step 2: test locally (always, before committing)

```bash
./mvnw -B verify          # PowerShell: .\mvnw.cmd -B verify
```

- **Why:** this runs all tests and builds the jar. If it fails here it would fail on GitHub too.
- **Check:** it ends with `BUILD SUCCESS` and the jar `target/plsql-atlas.jar` exists. (With `-q` it prints nothing on success.)

Then try the jar like a user would:

```bash
mkdir /tmp/try && cp target/plsql-atlas.jar /tmp/try/
printf 'create or replace procedure p is\nbegin\n  null;\nend;\n/\n' > /tmp/try/p.prc
cd /tmp/try && java -jar plsql-atlas.jar --no-ui      # expect: VERDICT: VERIFIED
```

## Step 3: bump the version (only when releasing)

The version number is stored in **three** places; change all three to the same value:

| File | What to change |
|---|---|
| `pom.xml` | the project `<version>` near the top |
| `src/main/java/dev/sandeep/plsqlparser/emit/AgentDocs.java` | `VERSION = "0.1.1"` |
| `src/main/java/dev/sandeep/plsqlparser/cli/Main.java` | `version = {"plsql-atlas 0.1.1", ...}` |

- **Check:** after a rebuild, `java -jar target/plsql-atlas.jar --version` prints the new number.

## Step 4: commit

```bash
git status                # see what changed; nothing unexpected (no corpus/, target/, sample PL/SQL)
git add -A
git commit -m "Release 0.1.1: plain ASCII console output"
```

- **Why `git status` first:** `.gitignore` already keeps `target/`, `corpus/` and `.agentdocs/` out, but look anyway.
- **Check:** `git log --oneline -3` shows your commit on top. Your name and email come from `git config user.name` / `user.email`; both must be your own (`codingpipeline@gmail.com`) because they become public.

## Step 5: push

```bash
git push
```

- **Check:** the output ends with `main -> main`, and the commit appears at <https://github.com/codingpipeline/plsql-atlas/commits/main>.

## Step 6: wait for CI to go green (do not skip)

Open <https://github.com/codingpipeline/plsql-atlas/actions>. The newest **CI** run belongs to your commit.

- Yellow dot = running (about 2 minutes). Green tick = passed on both Linux and Windows. Red cross = failed.
- Click the run, then the failed job, then the failed step to read the log.

No browser? The repo is public, so this works in Git Bash:

```bash
curl -s "https://api.github.com/repos/codingpipeline/plsql-atlas/actions/runs?per_page=1" | grep -E '"(status|conclusion|head_sha)"' | head -3
```

(`status: completed` and `conclusion: success` is what you want; compare `head_sha` with `git rev-parse HEAD`.)

### If CI fails: the real example from this project

The first CI run failed on Linux only, with the message `Process completed with exit code 126`.
Exit code 126 means "found the command but it is not executable". The file `mvnw` had been committed without the executable permission (Windows does not record it), so Linux could not run `./mvnw`.

The fix, which you can reuse for any script:

```bash
git update-index --chmod=+x mvnw      # mark it executable inside git
git commit -m "Make mvnw executable"
git push
```

Lesson: a green local build on Windows does not prove Linux works. That is exactly why CI runs on both.

## Step 7: tag the release

Only after CI is green on the commit you want to release:

```bash
git tag v0.1.1
git push origin v0.1.1
```

- **Why a tag:** the Release workflow starts only for tags beginning with `v`. The tag marks exactly which commit is being released.
- **Check:** `git ls-remote --tags origin` lists `refs/tags/v0.1.1`, and the Actions tab shows a new **Release** run (about 1-2 minutes).

## Step 8: check the release page

Open <https://github.com/codingpipeline/plsql-atlas/releases>. The new version should be on top, marked **Latest**, with two files:

- `plsql-atlas.jar` (about 6 MB)
- `SHA256SUMS` (the checksum, so users can check their download)

## Step 9: test the download like a user

This is the last and most important check: download the published file, not your local build.

```bash
mkdir /tmp/dl && cd /tmp/dl
B=https://github.com/codingpipeline/plsql-atlas/releases/latest/download
curl -L -o plsql-atlas.jar $B/plsql-atlas.jar
curl -L -o SHA256SUMS $B/SHA256SUMS
sha256sum -c SHA256SUMS              # expect: plsql-atlas.jar: OK
java -jar plsql-atlas.jar --version  # expect: plsql-atlas 0.1.1
```

The README's "latest release" link always points at the newest release, so nothing in the README needs editing per release.

---

## When something goes wrong

| Problem | What to do |
|---|---|
| Tagged too early / wrong commit, nobody downloaded it yet | `git tag -d v0.1.1` then `git push --delete origin v0.1.1`; delete the release on the Releases page; fix; tag again |
| Release workflow failed | the Release run on the Actions page shows which step; fix, commit, push, then delete and re-push the tag as above |
| Pushed a commit with a mistake | make a new commit that fixes it (`git revert <id>` undoes a pushed commit safely). Do not rewrite shared history |
| Wrong author email on a commit | fix `git config user.email` first, then amend the last commit only if it is not pushed yet |
| Need to see what you are about to push | `git log origin/main..main --oneline` (commits not on GitHub yet) |

## Checklist to print

- [ ] `./mvnw -B verify` passes locally
- [ ] jar smoke test says `VERDICT: VERIFIED`
- [ ] version changed in all three places
- [ ] `git status` clean of junk, author email is `codingpipeline@gmail.com`
- [ ] pushed, CI green on Linux **and** Windows
- [ ] tag pushed, Release run green
- [ ] Releases page shows the jar and `SHA256SUMS`
- [ ] downloaded jar passes `sha256sum -c` and `--version`
