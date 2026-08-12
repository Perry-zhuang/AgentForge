# AgentForge V0.2 P3 Verification — Codex Execution Guide

## Goal

Verify the remaining P3 gates for `agentforge prompt` without changing AgentForge behavior or the target IMP repository.

Current gate status:

```text
Scope Consistency       PASS
Fact Consistency        PASS
TaskType Behavior       PASS
Determinism             PASS
Read-only               TODO
Codex ANALYSIS 实战      TODO
mvn clean package       TODO
旧命令 regression       TODO
```

P3 can only be closed when all remaining gates pass.

Final decision must be exactly one of:

```text
Decision:
PROMPT READY FOR CHECK
```

or:

```text
Decision:
PROMPT NEEDS MORE TUNING
```

Do not enter Adapter work.

Next phase after P3 is:

```text
V0.2 P4 — Check / Verify
```

---

# 0. Hard Constraints

During this verification:

- Do not tune Inspect ranking.
- Do not tune Inspect confidence.
- Do not change Plan scope rules.
- Do not change Plan risk rules.
- Do not change Diff risk rules.
- Do not change Policy behavior.
- Do not change Architecture Scanner behavior.
- Do not redesign Prompt unless a clear P3 correctness bug is found.
- Do not modify the target IMP repository during Read-only verification.
- Do not reset, restore, clean, checkout, or discard existing IMP changes.
- Do not use `git reset --hard`.
- Do not use `git restore`.
- Do not use `git checkout -- <file>`.
- Do not use `git clean`.
- Preserve the existing IMP dirty working tree.

Target IMP repository:

```text
/Users/perrybook/IdeaProjects/imp
```

Expected IMP baseline:

```text
Branch:
feature/telecom_branch_zpf

HEAD:
48bf3d01e4f5a709c2e99542fa8626865b81e88f

Changed files:
42
```

Test task:

```text
分析备份历史告警同步
```

---

# 1. Read-only Verification

## Objective

Prove that running:

```bash
agentforge prompt \
  /Users/perrybook/IdeaProjects/imp \
  "分析备份历史告警同步" \
  --output /tmp/imp-analysis-readonly.md
```

does not modify the target IMP repository.

Read-only means:

```text
Before repository state
==
After repository state
```

The repository is allowed to already be dirty.

The test is not:

```text
repository must be clean
```

It is:

```text
existing dirty state must remain exactly unchanged
```

## 1.1 Capture BEFORE state

```bash
set -euo pipefail

REPO="/Users/perrybook/IdeaProjects/imp"

cd "$REPO"

git branch --show-current > /tmp/imp-branch-before.txt
git rev-parse HEAD > /tmp/imp-head-before.txt
git status --porcelain=v1 > /tmp/imp-status-before.txt
```

Capture repository file hashes excluding `.git`:

```bash
cd "$REPO"

find . \
  -path './.git' -prune -o \
  -type f -print0 \
  | sort -z \
  | xargs -0 shasum -a 256 \
  > /tmp/imp-files-before.sha256
```

## 1.2 Run AgentForge Prompt

```bash
agentforge prompt \
  /Users/perrybook/IdeaProjects/imp \
  "分析备份历史告警同步" \
  --output /tmp/imp-analysis-readonly.md
```

The output file must remain outside the IMP repository.

## 1.3 Capture AFTER state

```bash
cd "$REPO"

git branch --show-current > /tmp/imp-branch-after.txt
git rev-parse HEAD > /tmp/imp-head-after.txt
git status --porcelain=v1 > /tmp/imp-status-after.txt
```

Capture repository file hashes again:

```bash
cd "$REPO"

find . \
  -path './.git' -prune -o \
  -type f -print0 \
  | sort -z \
  | xargs -0 shasum -a 256 \
  > /tmp/imp-files-after.sha256
```

## 1.4 Compare

```bash
diff -u /tmp/imp-branch-before.txt /tmp/imp-branch-after.txt
diff -u /tmp/imp-head-before.txt /tmp/imp-head-after.txt
diff -u /tmp/imp-status-before.txt /tmp/imp-status-after.txt
diff -u /tmp/imp-files-before.sha256 /tmp/imp-files-after.sha256
```

All four comparisons must produce no differences.

## 1.5 Read-only Gate Result

Report:

```text
Read-only Verification

Branch unchanged        PASS/FAIL
HEAD unchanged          PASS/FAIL
Git status unchanged    PASS/FAIL
Repository file hashes  PASS/FAIL

Decision:
READ-ONLY PASS
```

Only report `READ-ONLY PASS` if all four checks pass.

If any check fails:

- show the exact difference;
- identify whether AgentForge caused it;
- do not repair or reset the IMP repository automatically.

---

# 2. Codex ANALYSIS Real-world Verification

Run this only after Read-only passes.

Generate the real prompt:

```bash
agentforge prompt \
  /Users/perrybook/IdeaProjects/imp \
  "分析备份历史告警同步" \
  --output /tmp/imp-analysis.md
```

Use `/tmp/imp-analysis.md` exactly as the Coding Agent instruction.

Do not rewrite or manually improve the generated prompt before testing it.

## Verification Points

Check whether the Coding Agent:

1. reads PRIMARY first;
2. expands to SECONDARY only when needed;
3. treats REFERENCE as read-only context by default;
4. does not modify source code for ANALYSIS by default;
5. protects the existing 42 dirty files;
6. follows the Related Flow;
7. does not silently expand scope;
8. reports any scope deviation;
9. produces the required Final Report.

Expected ANALYSIS behavior:

```text
Trace
Verify
Report

Do not modify source code by default.
```

Expected Final Report structure:

```text
1. Root Cause / Findings
2. Files Read
3. Files Modified
4. Why Each File Was Modified
5. Tests Executed
6. Test Results
7. AgentForge Diff Result
8. Remaining Risks
9. Scope Deviations
```

If no repository changes are made, expected report semantics are:

```text
Files Modified:
None

AgentForge Diff Result:
Not run — no changes
```

## Codex ANALYSIS Gate Result

Report:

```text
Codex ANALYSIS Real-world Verification

PRIMARY-first behavior          PASS/FAIL
SECONDARY expansion behavior    PASS/FAIL
REFERENCE read-only behavior    PASS/FAIL
ANALYSIS no-source-change       PASS/FAIL
Dirty tree protection           PASS/FAIL
Related Flow behavior           PASS/FAIL
Scope control                   PASS/FAIL
Final Report contract           PASS/FAIL
Scope Deviation reporting       PASS/FAIL

Decision:
CODEX ANALYSIS PASS/FAIL
```

If a failure occurs, distinguish:

```text
Prompt contract issue
```

from:

```text
Coding Agent instruction-following issue
```

Do not automatically redesign Inspect or Plan because of an Agent compliance failure.

---

# 3. Maven Build Verification

After the two gates above pass, run in the AgentForge repository:

```bash
./mvnw clean package
```

Expected:

```text
BUILD SUCCESS
```

Acceptance:

```text
Failures = 0
Errors   = 0
```

The exact total test count may be greater than the previous 156 tests.

Do not require the test count to remain exactly 156.

Report:

```text
mvn clean package

Build              PASS/FAIL
Tests              PASS/FAIL
Failures           <count>
Errors             <count>
Skipped            <count>

Decision:
MAVEN PACKAGE PASS/FAIL
```

---

# 4. Old-command Regression Verification

Run the existing commands against the same IMP repository and task.

```bash
agentforge audit /Users/perrybook/IdeaProjects/imp
```

```bash
agentforge inspect \
  /Users/perrybook/IdeaProjects/imp \
  "分析备份历史告警同步"
```

```bash
agentforge plan \
  /Users/perrybook/IdeaProjects/imp \
  "分析备份历史告警同步"
```

```bash
agentforge diff \
  /Users/perrybook/IdeaProjects/imp
```

Verify that P3 did not change earlier behavior.

Required invariants:

```text
Inspect Ranking            UNCHANGED
Inspect Confidence         UNCHANGED
Plan Scope                 UNCHANGED
Plan Risk                  UNCHANGED
Diff Risk                  UNCHANGED
Policy Behavior            UNCHANGED
Architecture Scanner       UNCHANGED
```

For the known IMP ANALYSIS case, assuming the IMP repository baseline has not changed, expected scope remains:

```text
PRIMARY

1. SysAlertServiceImpl
2. TimerTaskImpl
3. SysWarnServiceImpl
```

Expected risk remains:

```text
HIGH / 40
```

Expected factors:

```text
42 dirty files          +20
PRIMARY overlap         +15
Architecture missing     +5
                         ---
Total                    40
```

If the repository baseline itself changed, do not falsely call the regression failed.

First determine whether the difference is caused by:

```text
repository facts changed
```

or:

```text
AgentForge P3 changed old behavior
```

## Regression Gate Result

Report:

```text
Old-command Regression

audit                         PASS/FAIL
inspect                       PASS/FAIL
plan                          PASS/FAIL
diff                          PASS/FAIL

Inspect Ranking unchanged     PASS/FAIL
Inspect Confidence unchanged  PASS/FAIL
Plan Scope unchanged          PASS/FAIL
Plan Risk unchanged           PASS/FAIL
Diff Risk unchanged           PASS/FAIL
Policy Behavior unchanged     PASS/FAIL
Architecture Scanner unchanged PASS/FAIL

Decision:
OLD COMMAND REGRESSION PASS/FAIL
```

---

# 5. Final P3 Gate

At the end, report the complete matrix:

```text
Scope Consistency       PASS
Fact Consistency        PASS
TaskType Behavior       PASS
Determinism             PASS
Read-only               PASS/FAIL
Codex ANALYSIS 实战      PASS/FAIL
mvn clean package       PASS/FAIL
旧命令 regression       PASS/FAIL
```

If every item is PASS, output exactly:

```text
Decision:
PROMPT READY FOR CHECK
```

If any item fails, output exactly:

```text
Decision:
PROMPT NEEDS MORE TUNING
```

Do not start Adapter implementation.

The next phase after a successful P3 close is:

```text
V0.2 P4 — Check / Verify
```

---

# 6. Final Verification Report

Use this structure:

```text
# AgentForge V0.2 P3 Final Verification

## 1. Read-only Verification
...

## 2. Codex ANALYSIS Real-world Verification
...

## 3. Maven Build Verification
...

## 4. Old-command Regression
...

## 5. Invariants
Inspect Ranking:
Inspect Confidence:
Plan Scope:
Plan Risk:
Diff Risk:
Policy Behavior:
Architecture Scanner:

## 6. Final Gate

Scope Consistency       PASS
Fact Consistency        PASS
TaskType Behavior       PASS
Determinism             PASS
Read-only               PASS/FAIL
Codex ANALYSIS 实战      PASS/FAIL
mvn clean package       PASS/FAIL
旧命令 regression       PASS/FAIL

Decision:
PROMPT READY FOR CHECK
```

Do not claim PASS without command evidence.
