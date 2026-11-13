# SoPhi over HTTP (sophi-web)

[← Back to the README](../README.md)


Run SoPhi as a Spring Boot service so a frontend, another service, or a
mobile app can talk to it over REST/SSE.

```bash
mvn -pl sophi-web -am spring-boot:run
```

Endpoints (`AgentController`, base path `/api`):

```
POST /api/sessions?title=...          create a session -> {id, entryCount, lastModified}
GET  /api/sessions                    list sessions
POST /api/sessions/{id}/turn          { "input": "..." } -> { sessionId, reply }
GET  /api/sessions/{id}/stream?input=...   Server-Sent Events, one event per token
POST /api/sessions/{id}/feedback      { "polarity": "positive|negative", "reason"?, "entryIndex"? } -> rate a reply
```

Example:

```bash
curl -X POST localhost:8080/api/sessions
curl -X POST localhost:8080/api/sessions/<id>/turn \
  -H 'Content-Type: application/json' -d '{"input": "hello"}'
curl -N "localhost:8080/api/sessions/<id>/stream?input=hello"   # SSE stream
```

**Good for:** powering a chat UI, a Slack/Discord bot, or any client that
needs the agent behind a network boundary rather than in-process.

