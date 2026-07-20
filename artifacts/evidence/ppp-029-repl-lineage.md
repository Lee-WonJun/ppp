# PPP-029 REPL Lineage Evidence

Date: 2026-07-20 KST

## Source check

- GNU Emacs states that an Emacs Lisp interpreter is at its core.
- Stallman's 1981 paper states that Emacs can be extended while running by
  adding or replacing functions.
- The official nREPL overview describes a network REPL used by editors and
  other tools to evaluate and inspect running environments.
- Official nREPL server guidance warns that the default unauthenticated server
  must not be exposed on a public address because clients can modify the
  application or execute code on the host.
- JPL records that a bug interrupted the first Deep Space 1 Remote Agent
  experiment, that diagnosis was a benefit of the test, and that the follow-up
  experiment could safely continue without fixing the flight bug.
- Ron Garret's first-hand account describes using the spacecraft Lisp REPL to
  diagnose the wedged state and inject an event that allowed the scenario to
  continue.

## Editorial decision

The README and submission say “diagnose and un-wedge,” not “permanently patch
the spacecraft.” nREPL is credited as tooling inspiration, while the text
explicitly states that PPP exposes neither nREPL nor a general evaluator.

This keeps the memorable lineage while reinforcing PPP's actual contribution:
a nontechnical product surface around live programming with validation,
capability limits, atomic activation, history, and recovery.

The judge-facing comparison also separates PPP from ordinary hot reload. Hot
reload remains a developer file-watcher/module-replacement workflow. PPP owns
source generation, cross-runtime validation, SQLite staging, exact-version
activation, history, and source-plus-data recovery for a nontechnical author.

## Public project synchronization

The existing public Devpost description was read and only its prior lineage
paragraph was replaced. Devpost accepted project version `4`. After the owner
requested an explicit hot-reload distinction, a guarded insertion added that
comparison and Devpost accepted version `5`. Neither update added a video,
changed links or technologies, exposed judge access, or submitted the OpenAI
Build Week entry.

## Sources

- https://www.gnu.org/software/emacs/
- https://www.gnu.org/software/emacs/emacs-paper.html
- https://www.gnu.org/software/clisp/clisp.html
- https://nrepl.org/nrepl/index.html
- https://nrepl.org/nrepl/usage/server.html
- https://www.jpl.nasa.gov/nmp/ds1/tech/autora.html
- https://corecursive.com/lisp-in-space-with-ron-garret/

## Verification

- Public Devpost read: `state=published`, `submitted_at=null`,
  `video_url=null`, with nREPL, DS1 precision, bounded-endpoint, and hot-reload
  distinction phrases present.
- GNU Emacs, Stallman paper, GNU CLISP, nREPL overview, and nREPL server links
  returned HTTP 200. The legacy JPL page is indexed and readable through the
  source review but rejects command-line retrieval with its site access policy;
  its public URL remains the canonical JPL record.
- `bb lint`: zero errors and zero warnings.
- `bb format-check`: all source files formatted correctly.
- `git diff --check`: clean.
