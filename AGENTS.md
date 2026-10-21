# Working on werwolf-clj

Werewolf in the browser: a Clojure server, a ClojureScript (re-frame) client,
and an engine in `.cljc` shared by both. German is the main language of the UI.

## Work in a REPL, not in fresh JVMs

Starting a JVM costs minutes; a running REPL answers in milliseconds. Use one
REPL for the whole session.

- Start it once: `bb dev:repl` (nREPL with the `:dev:cljs:test` aliases, port
  in `.nrepl-port`), then `(require 'dev) (dev/go)`. If someone else's REPL is
  already running, ask before evaluating in it.
- Server code: `(dev/reset)` reloads changed namespaces and restarts the
  system. Inspect live rooms with `(dev/system)` instead of reading SQLite or
  logs.
- Reloading only reloads CHANGED files, and that is not always enough.
  `werwolf.build-info` bakes the commit in at macro-expansion time, so after
  a commit the unchanged build-info test keeps failing in the REPL with the
  old commit. Use `(clojure.tools.namespace.repl/refresh-all)` for the full
  suite and whenever HEAD has moved; `(require 'the.ns :reload)` for one
  namespace. `kaocha.repl/run` does not reload a test namespace by itself
  either: refresh first, or a changed test runs in its old shape.
- A headless nREPL (no terminal attached) cannot be started with
  `clojure -M:dev:cljs:test -m nrepl.cmdline ...`: the `:test` alias's
  `:main-opts` win and hand the arguments to kaocha. Use a throwaway alias
  through `-Sdeps` with the dev deps and `:main-opts ["-m" "nrepl.cmdline"
  "--port" "7899"]`, over `-M:cljs:<that-alias>`.
- Client code: run shadow-cljs inside the same JVM
  (`(shadow.cljs.devtools.server/start!)`, `(shadow.cljs.devtools.api/watch :app)`)
  and check a change with `(shadow.cljs.devtools.api/watch-compile-all!)`, which
  prints warnings and errors. A broken compile can otherwise go unnoticed
  (an unescaped `"` inside a docstring did).
- Tests: run the namespace you touched from the REPL,
  `(kaocha.repl/run 'werwolf.room-test)` or `(clojure.test/run-tests ...)`.
  Run the full suites once at the end: `bb test`, `bb test:cljs`,
  `bb check:app`, `bb test:browser`.
- Tailwind only generates classes it finds spelled out in the sources. After
  adding classes, rebuild the CSS (`bb build:css`, or `bb dev:css` to watch).

## How the code is meant to be

- **The client contains no game rules.** Every button and every pickable card
  comes from `:actions`, `:targets` and `:arity`, which the server computes with
  the engine's own legal-actions. If the UI needs to know something, the server
  sends it as a fact in the view (`:night-plan`, `:votes-needed`, ...); the
  client does not work it out.
- **The wire carries structured events, never sentences.** Every reader renders
  them in their own language. Each text exists in `i18n/de.cljc` and
  `i18n/en.cljc` (a test fails if a key is missing from either). German is the
  reference; write it naturally, not as a translation.
- **Redaction is the server's job.** A player's view must never contain
  another player's secret role. Tests check what was sent over the wire, not
  only what the UI shows.
- **Comments explain why**, often with the playtest or bug that caused a
  decision. Keep that style and keep that density; do not narrate what the code
  does.
- **Test hooks:** `data-testid` only where a browser test cannot find an
  element by its role or text. Browser tests run at desktop width.
- **Text inputs are uncontrolled** (`views/field`). Never give an input a
  `:value`; see the docstring for the Android bug this prevents.

## Version control: jujutsu

The repo is colocated jj + git. A detached HEAD in git is normal.

- One change per idea: `jj new` before starting the next thing.
  Fix earlier changes with `jj squash --into <id>` or `jj edit <id>`.
- Abandon only by exact change id, never by a revset pattern.
- Push with `jj git push -c <rev>`, which creates `push-<change id>` branches.
  Open a PR for each one as soon as it is ready, so CI runs early. Related
  changes go into a stack: each PR's base is the branch below it.
- Never push `main` directly.
- Commit messages follow Conventional Commits (`feat`, `fix`, `ci`, `build`,
  `chore`, ...); release-please builds the changelog and version from them.
  Subject in the imperative, a body that explains why.
- The repo is public. No real names (playtesters included), no private email
  addresses, hostnames, IPs, tokens or session links in commits or files.

## Build, CI and previews

- Nix builds the package (`nix/package.nix`). The build must stay
  byte-identical for identical code: the commit is **not** compiled into the
  package. It reaches the server at runtime through `WERWOLF_COMMIT`, which
  `services.werwolf.commit` sets. Do not add anything that changes per commit
  to a derivation.
- Every push to a `push-*` branch builds a preview image. lain's controller
  runs open PRs at `https://pr-<n>.werwolf-chrz.duckdns.org`: at most four at
  a time, the most recently active first.
- Do not wait on CI by polling; push, report and continue.

## Processes

Stop processes by the PID you started (`cmd & echo $! > pidfile`). Never use
`pkill -f` or `killall` with a pattern: on macOS, options placed after the
pattern count as more patterns and match unrelated programs.
