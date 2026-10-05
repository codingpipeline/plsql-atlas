# plsql-atlas — Complete Plan

> Living document. Status legend: ✔ done · ▶ in progress · ○ planned. Last updated: M1–M7 complete (nothing published until the owner gives the repo name).

## 1. Purpose
Run **one command on any folder of Oracle PL/SQL (< 50 files)** and get documentation that lets **any AI coding
agent** fully understand complex PL/SQL — ready for refactoring into whatever
target the team chooses later.

Deliberately **target-agnostic**: this tool never generates Java/Quarkus/anything, never calls an LLM and never
connects to a database. It does exactly one thing: *parse PL/SQL completely and dump structured, lossless,
agent-consumable documentation* (plus a human UI to verify it).

Inspired by [codebase-memory-mcp](https://github.com/DeusData/codebase-memory-mcp) (graph + viewer) but PL/SQL-only,
doc-export-first and far simpler because the scale is small. Oracle's
[PL/SQL skills](https://github.com/oracle/skills/tree/main/db/plsql) are the domain checklist (cursors, collections,
error handling, packages, compiler options, security, performance patterns).

## 2. Decisions
| Decision | Choice | Why |
|---|---|---|
| Language | **100 % Java 21**, Maven, one fat jar | Fast (JIT + ANTLR Java target), no Python in the target folder, easy `java -jar` |
| Parser | ANTLR4 + antlr/grammars-v4 PlSql grammar (Java target), generated at build time | Most complete open PL/SQL grammar; 7 real files parse with 0 errors |
| Conditional compilation | Own pre-pass: `$IF/$ELSIF/$ELSE/$END/$ERROR` → *primary* + *alternate* variants, line numbers preserved | Grammar can't parse directives; no branch may be silently lost |
| Storage | In-memory model, dumped to files | < 50 files: no DB, no incremental indexing |
| Output | Markdown + JSON + Mermaid + static HTML Explorer | Agent-readable and human-verifiable |
| Scope guard | No code generation, no LLM calls, no DB connection | Keep the tool about *understanding* |

## 3. Usage (the whole product)
```bash
java -jar plsql-atlas.jar                # M7: scan this folder → ./.agentdocs/ + verdict + live UI (http://127.0.0.1:9000)
java -jar plsql-atlas.jar --no-ui        # build + verify only (CI); exit code 1 if NOT VERIFIED
java -jar plsql-atlas.jar --watch        # rebuild on file change, live feed in the UI
# available today
java -jar … check|outline|graph|risks <folder>
```

## 4. Architecture
```
files → Discover → Preprocessor ($IF variants) → ANTLR parse (parallel, SLL→LL)
      → StructureExtractor (M2)  units, routines, params, decls, nested subprograms, comments
      → Linker (M2)              spec↔body, visibility, overloads, stable ids
      → SqlExtractor (M3)        tables+access, columns, binds, dynamic SQL
      → CallExtractor+Resolver (M3)   local / internal / Oracle built-in / external / unresolved
      → OutlineBuilder (M4)      lossless control-flow outline, exception contract, side effects, metrics
      → RiskRules (M4)           Oracle-specific hazards
      → DependencyGraph (M3)     recursion, dead code, table impact, Mermaid
      → Emitters (M5)            .agentdocs/ cards, AGENTS.md, coverage proof
      → Explorer (M6)            offline HTML UI + prompt packs
```
Package layout: `dev.sandeep.plsqlparser.{cli,parse,extract,model,analyze,emit,grammar}`.

## 5. Target output: `.agentdocs/`
```
AGENTS.md            entry point: reading order, conventions, legend, how to use cards
OVERVIEW.md          whole-system summary + Mermaid dependency diagram + tables touched
INDEX.json           every object: kind, file, lines, purpose, complexity, risks, conversion-order rank
objects/<pkg>/_package.md       public API, types, constants, state, init block, deps in/out
objects/<pkg>/<routine>.md|json ROUTINE CARD
tables/<t>.md        readers/writers, columns, DML kinds, triggers
analysis/            migration-order.md  risks.md  coverage.md
source/              original files, untouched (cards cite file:line)
explorer.html        UI
```
### Routine card contents
1. Signature: params (mode, type, default), return, overload id, attributes (pipelined, deterministic, result_cache, autonomous txn, AUTHID)
2. Deterministic purpose summary (names + comments)
3. **Numbered logic outline** — IF/ELSIF/CASE/loops/cursor loops/FORALL/EXIT/CONTINUE/GOTO/RETURN/RAISE/handlers, nested, with line spans; lossless w.r.t. control flow
4. Every SQL statement verbatim + tables/columns read/written + binds + in-loop flag
5. Calls out (resolved) and called-by
6. State & side effects: package globals touched, commit/rollback/savepoint, autonomous txn, DDL, dynamic SQL, sequences, files/network/scheduler
7. Exception contract: raised / handled / `WHEN OTHERS` behaviour / `RAISE_APPLICATION_ERROR` codes
8. **Oracle semantics notes**: NULL vs '', `NO_DATA_FOUND`/`TOO_MANY_ROWS`, `%TYPE/%ROWTYPE`, ROWNUM/ROWID, DECODE/NVL, SYSDATE, collections, `BULK COLLECT/FORALL SAVE EXCEPTIONS`, cursor attributes, `$IF`
9. Embedded original source span
10. Open questions: dynamic SQL, unresolved refs, ambiguous overloads, undecided `$IF` flags

## 6. Correctness guarantees ("no missed logic")
- **Grammar-driven allowlist**: every statement kind of the `statement` rule maps to an outline step; unmapped ⇒ `UNKNOWN` step + issue + failing test.
- **Statement-count proof**: per routine, #`statement` parse nodes == #outline steps from statements (M4).
- **Per-file line coverage** and **round-trip** (card spans re-assemble the file) (M5).
- Everything not decomposed is *listed*, never dropped: skipped DDL, unparsed regions, dynamic SQL (`LITERAL/PARTIAL/UNKNOWN`), unresolved calls, ambiguous overloads, undecided `$IF` conditions, alternate-variant-only routines.

## 7. Milestones
| # | Scope | Status |
|---|---|---|
| M1 | Maven/Java project, ANTLR parser, `$IF` pre-pass, real-world corpus test, CI, license/NOTICE | ✔ |
| M2 | Structure: units, routines, params, vars/consts, types, cursors, exceptions, pragmas, nested subprograms, comments, spec↔body, overload ids, `outline` command | ✔ |
| M3 | SQL read/write sets, dynamic SQL, call resolution, dependency graph, impact queries, Mermaid, `graph` command | ✔ |
| M4 | Logic outline, exception contract, side effects, complexity metrics, risk rules, `risks` command, `outline --logic` | ✔ |
| M5 | `.agentdocs/` emitter: cards, AGENTS.md, OVERVIEW, tables, migration order, risks, coverage proof; `build` command | ✔ |
| M6 | Live Explorer UI: `explore` command (localhost server, port 9000+, reads `.agentdocs/` on every request, SSE refresh on rebuild); tree/search, card + source pane with step↔line highlight, animated Cytoscape focus/global graph, table impact, order board, coverage dashboard, **confidence score + missing-artifact view** (`analysis/confidence.json/.md`), copy-prompt-pack with token estimate | ✔ |
| M7 | Drop-in release: `go` default command (`java -jar x.jar` in the PL/SQL folder), shared `Pipeline` with live progress feed (SSE) and overlay, `Verifier` + verdict page (`analysis/verification.*`), file-to-file dependency map (`analysis/file-dependencies.*`, incl. tables-created-in), ER diagram from DDL + join inference (`analysis/er-diagram.md`, `er.json`, ER view), robustness (syntax errors no longer crash the build), README rewrite with screenshots (of a made-up sample project; the sample files themselves are not shipped) + Mermaid architecture, `release.yml` (tag → jar + SHA-256) and a drop-in smoke test in CI that creates its two tiny inputs inline | ✔ (jar published only when the owner pushes a tag) |

### M4 detail (done)
- `LogicStep` tree per routine (and per package init block / trigger body / anonymous block).
- Exception contract: explicit raises, `RAISE_APPLICATION_ERROR` codes, re-raises, handlers (`WHEN OTHERS`, swallow detection), implicit `NO_DATA_FOUND`/`TOO_MANY_ROWS` from `SELECT INTO`.
- Side effects: commit/rollback/savepoint, autonomous transaction, package-state reads/writes, sequences, DDL / implicit commit, dynamic SQL, locks, DB links, categorised Oracle built-in use (file, network, scheduler, IPC…).
- Metrics: statements, decision points, cyclomatic complexity, max nesting, loops.
- Risk rules (see §8).

## 8. Risk catalogue (from the Oracle PL/SQL skills checklist)
`WHEN_OTHERS_SWALLOWED`, `WHEN_OTHERS_NULL`, `DYNAMIC_SQL_CONCATENATION`, `DDL_IN_PLSQL`, `COMMIT_IN_ROUTINE`,
`AUTONOMOUS_TRANSACTION`, `ROW_BY_ROW_DML`, `SQL_IN_LOOP`, `UNBOUNDED_BULK_COLLECT`, `PACKAGE_STATE_WRITE`,
`SELECT_INTO_NO_HANDLER`, `APP_ERROR_CODE_OUT_OF_RANGE`, `WHEN_OTHERS_RETURNS_DEFAULT`, `DYNAMIC_SQL_OPAQUE`, `GOTO_USED`, `HIGH_COMPLEXITY`, `DB_LINK`, `OPTIMIZER_HINT`, `ROW_LOCKING`,
`EXTERNAL_SIDE_EFFECT` (UTL_FILE/UTL_HTTP/UTL_SMTP/DBMS_SCHEDULER/DBMS_JOB/DBMS_PIPE/…), `UNRESOLVED_CALL`.

## 9. Testing & verification
- Unit tests per construct (inline PL/SQL fixtures) and golden outputs; real-world corpus (Logger, Alexandria) via `scripts/fetch-real-world-corpus.*`.
- Completeness tests: statement allowlist, statement-count proof, no `UNKNOWN` steps on the corpus.
- End to end (M5+): run `build` on a fresh 20–40 file folder; hand one card to a coding agent cold and compare its explanation to the source; open the Explorer and trace a routine across views.
- CI: GitHub Actions, Linux + Windows, JDK 21.

## 10. GitHub showcase checklist
Conventional commits per milestone · README (problem, quickstart, before/after card, architecture diagram, results on real code, honest limitations, roadmap) · MIT license + NOTICE (grammar attribution + local patch note) · CI badge · sample output committed · release with the fat jar. **Nothing is pushed until the owner gives the repo name/visibility.**

## 11. Known limitations (honest list)
- Overloads are resolved by argument count/names only, not argument types (ambiguous ones stay as candidates).
- Column attribution for unqualified columns only when a statement has exactly one table.
- Package-state tracking ignores writes through `OUT` arguments.
- Object type bodies, views and other DDL are listed as skipped, not decomposed.
- Static analysis only: synonyms/views/grants cannot be resolved without a database dictionary (an optional dictionary-export import is a possible later addition).
