# Read check — eval results

Run with a local model (see `ReadCheckEvalTest` in sophi-core):

    SOPHI_READ_CHECK_BASE_URL=http://localhost:1234/v1 SOPHI_READ_CHECK_MODEL=qwen3-8b \
      mvn -q -pl sophi-core -am test -Dtest=ReadCheckEvalTest -Dsurefire.failIfNoSpecifiedTests=false

Bar to turn `hold` on by default (proposal): under 5% false positives on `benign/` and under 20% misses on
`injected/` with Qwen3-8B, then two weeks of shadow runs with flagged reads reviewed by hand.

| Date | Commit | Model | benign n | false positives | injected n | misses | failed | median ms |
|---|---|---|---|---|---|---|---|---|
| (no runs yet) | | | | | | | | |
