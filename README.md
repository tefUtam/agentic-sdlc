# Agentic SDLC Orchestrator (Java 21, zero dependencies)

Orchestrates a requirement through requirements, design, implementation, verification, documentation and release readiness
as an **explicit dependency graph** with entry/exit gates, parallel branches with barriers, policy guardrails, human approval
checkpoints, an audit-grade hash-chained event log, and fail-safe stopping. The URL shortener is the workload.

Status: **build steps 1-3 of the design doc are done** (core model, scheduler/gates/run loop, policy engine, approvals).
Retries/fallback/rollback, re-planning, the real shortener code, full metrics and the brownfield scenario are next (see "Not yet built").

## Quick start

Requirements: JDK 21 (a full JDK, or a JRE that includes the `jdk.compiler` module; `build.sh` handles both). No Maven, no network.

```bash
./build.sh                       # compile main + tests
./test.sh                        # 71 tests, ~5 s
./run.sh --auto-approve          # greenfield run, approvals auto-granted (demo mode)
./run.sh --scenario ambiguous --auto-approve   # vague requirement: stops at the ambiguity gate
./run.sh                         # real human-in-the-loop: approve via HTTP (curl commands are printed)
./run.sh --cli-approve           # approve at the terminal instead
./run.sh --help
```

Manual approval over HTTP (loopback only, default port 8080):

```bash
curl -s http://127.0.0.1:8080/approvals                       # what is waiting for me?
curl -s -X POST http://127.0.0.1:8080/approvals/APR-001/decision \
     -d 'decision=APPROVE&approver=alice&rationale=requirements+match+intent'
curl -s http://127.0.0.1:8080/status                          # node states
```

Every run writes `runs/<runId>/`: `events.jsonl` (hash-chained audit log), `state.json` (node states, rewritten atomically
on every transition) and `artifacts/` (every artifact version, including rejected ones).

## How it works

```
 CLI / HTTP approvals --> ApprovalManager --inbox--+
                                                   v
 Dag (nodes, edges, invariants) --> Orchestrator (single coordinator thread)
                                        |  dispatch            ^ results
                                        v                      |
                                   worker pool (agents) -------+
 Gate (entry/exit checks)   PolicyEngine (SEC-001, REL-001)   ArtifactStore   EventLog (hash chain)
```

- **Graph as data** (`dag`): 19 nodes, edge kinds HARD / SOFT / SYNC, node kinds AGENT / APPROVAL / SYNC / FINAL.
  `Dag.validate()` enforces: acyclic; one producer per artifact type and every input has its producer upstream; HIGH-risk agent
  nodes have an approval upstream; concurrent nodes have disjoint write scopes; exactly one FINAL sink; barriers join 2+ branches.
  An invalid graph is refused before any agent runs.
- **State machine** (`StateMachine`): the only way node state changes; illegal transitions throw.
- **Single-writer coordinator**: one thread owns all transitions, gates, policy checks and commits; agent calls run on a bounded
  pool and report through an inbox queue; human approvals arrive on the same queue. Strictly ordered audit trail, no locks on node state.
- **Gates are deterministic code, not model judgement.** Exit gates check the agent's output (schema markers, test results, coverage
  >= 80, no high findings, ambiguity score <= 0.6). Agents cannot grade themselves.
- **Policy before persistence**: SEC-001 scans outputs *before* they are written, so a leaked secret never reaches disk.
  REL-001 re-reads the verification artifacts independently before a human is even asked to approve a release (defence in depth).
  A rule returning REQUIRE_APPROVAL puts an agent node behind a human checkpoint (tested).
- **Approvals bind to content**: a request carries the hash of what is being approved; a stale hash is refused (HTTP 409).
- **Safe-stop**: any gate failure, policy denial, agent error, entry-gate failure, deadlock, or timeout halts scheduling, cancels open
  approvals, parks unstarted nodes, discards late in-flight results, and records the first cause. Human rejection aborts the run.
- **Lineage**: every artifact lists its parents; every agent/human choice is a `Decision` linked to the artifacts it shaped
  (e.g. "302 not 301 so clicks are counted" -> `ARCH_DOC:v1`).
- **Agents** sit behind `LlmClient`. The default `StubLlmClient` is deterministic and returns canned but structurally realistic
  artifacts, so runs and tests are reproducible. Plug a real model in by implementing that one interface.

## Scenarios

| Scenario | Command | What it shows |
|---|---|---|
| Greenfield | `./run.sh --auto-approve` | full graph, 3 parallel fan-outs, barriers, 3 approvals, lineage, audit chain |
| Ambiguous | `./run.sh --scenario ambiguous --auto-approve` | ambiguity gate fails (0.85 > 0.6), run safe-stops, rejected report kept as evidence |
| Brownfield | not yet | needs impact analysis + re-planning (next build steps) |

## Testing approach

71 tests in `src/test`, run by a small reflection-based runner because JUnit is unavailable offline (test methods are plain
`public void testX()` and port to JUnit 5 by adding annotations). Coverage by layer: state machine, DAG invariants and scheduler,
hash-chain tamper/removal/reorder detection, artifact store and lineage, gates, policy rules and engine, approval manager,
the HTTP API (status codes, stale hash, loopback bind), and 17 end-to-end orchestrator tests including parallelism,
barrier ordering, each safe-stop trigger, late-result discarding, rejection, policy-gated nodes and an HTTP-driven run.
Spot-checked by mutation: disabling SEC-001 or the exit gates makes the relevant tests fail. The suite ran 6 times in a row without flakiness.

## Differences from the design doc (to reconcile)

- **Stack: Java, not Python/FastAPI** (your decision). **Persistence: JSONL + files, not SQLite**: no JDBC driver is available offline.
  `EventLog` and `ArtifactStore` are concrete classes; moving to SQLite means changing their internals, not their callers.
- Artifact types: `CODE_PATCH` is split into `CODE_CORE` / `CODE_ANALYTICS` (one producer per type is an enforced invariant);
  `COVERAGE_REPORT` is folded into `UNIT_TEST_REPORT`; `UNIT_TEST_REPORT` and `INTEGRATION_TEST_REPORT` are separate.
- `EXIT_GATE_EVAL` is not an observable node state: gates run atomically on the coordinator. `RETRYING` and `STALE` exist in the
  state machine but nothing drives them yet.
- A policy `REQUIRE_APPROVAL` on *post-action* is treated as a failure (only pre-action approval gating is implemented).

## Not yet built (design-doc build order)

- **Step 4**: bounded retries with failure evidence, fallback, workspace rollback. Today every failure is a safe-stop.
  `REQUEST_CHANGES` approvals return HTTP 501 for the same reason.
- **Step 5**: re-plan engine (stale propagation, voiding approvals when an input hash changes, minimal re-execution).
- **Step 6**: the real URL shortener implementation, generated through the graph. Today artifacts are stub text, so a green run
  proves the orchestration, not code quality. Policy CHG-001 (write-scope enforcement) also waits for agents that write files.
- **Step 7**: full reliability metrics. Present now: latency split (agent busy vs human wait), peak parallelism, approvals requested.
  Retry/rollback frequency and MTTR need step 4.
- **Step 8**: brownfield scenario and final engineering summary.

## Known limitations and trade-offs

- Approver identity is self-asserted; the HTTP API has no authentication (it only binds to loopback). Not production auth.
- Single process, single coordinator: simple and correct, not horizontally scalable. A run cannot yet resume from `state.json`
  after a crash (state is persisted, resume logic is not).
- Gate and policy checks are regex/marker based: they catch the failures we encode, they do not prove correctness.
- `allowedPaths` is validated for graph-level disjointness but not enforced at runtime (no agent touches a filesystem yet).
- Stub latency is simulated sleep; timing numbers are illustrative.
