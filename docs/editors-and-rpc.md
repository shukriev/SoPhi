# SoPhi in your editor, and over JSON (ACP and RPC)

[← Back to the README](../README.md)

The same agent the terminal runs can be driven by another program over stdin/stdout.

## In your editor (ACP)

`sophi --mode acp` speaks the [Agent Client Protocol](https://agentclientprotocol.com) (protocol version 1),
so Zed and other ACP clients can drive SoPhi. In Zed's `settings.json`:

```json
{
  "agent_servers": {
    "SoPhi": {
      "type": "custom",
      "command": "/absolute/path/to/sophi",
      "args": ["--mode", "acp"]
    }
  }
}
```

Then pick **SoPhi** in Zed's Agent Panel. Use the absolute path: an app started from the Dock may not have
your shell's `PATH`. `dev: open acp logs` in Zed's command palette shows every message, if something looks wrong.

- SoPhi works in the directory the editor starts it in (the project root).
- Risky tool calls ask through the editor's Allow / Reject prompt.
- Changes held as a plan (see [plan first](extending.md#plan-first-approve-changes-before-they-happen)) show as
  one "Apply N held change(s)" prompt listing the changes. Allow replays them; Reject or cancel discards them.
  Add `"--plan-first", "every"` to `args` to hold local file changes too.
- When a turn stops early (tool-round limit, output cut off, repeated failures) the editor gets a one-line reason.
- Each new editor thread is a new SoPhi session, with the same start and end work (hooks, learning) as the terminal.

Not supported yet: loading past sessions, images, MCP servers passed by the editor (use the project's
`.sophi/mcp.json`), and the editor's own file and terminal access (SoPhi uses its own tools).

## Over JSON lines (RPC)

`sophi --mode rpc` reads one JSON command per line on stdin and writes one JSON event per line on stdout, so
any language can drive it. The eval suite (`evals/playbook/run.sh`) is a small bash client of it.

Commands (`type`): `prompt`, `follow_up`, `steer`, `abort`, `confirm` (`callId`, `approved`), `compact`,
`branch`, `checkout` (`entryId`, `files`), `set_model`, `goal` (`text`, `check`), `plans`, `approve_plan`
(`planId`), `cancel_plan` (`planId`). Any command may carry an `id`, echoed in its `ack` or `error`.

Events (`type`): `ready`, `turn_started`, `token`, `reasoning_token`, `tool_call_started`,
`tool_call_finished`, `confirmation_requested`, `confirmation_resolved`, `steer_applied`,
`follow_up_started`, `model_fallback`, `usage`, `plan_ready` (`planId`, `steps`, `changes`), `turn_ended`
(`reply`, `stopReason`), `ack`, `error`, `exited`.

```bash
printf '%s\n' '{"type":"prompt","text":"list the files here"}' | sophi --mode rpc
```

The protocol types live in `dev.sophi.sdk.rpc` (`RpcProtocol.kt`), so a JVM host can reuse them.
