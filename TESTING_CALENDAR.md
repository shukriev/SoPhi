# Testing sophi-calendar's macOS backend yourself

This covers manually verifying the six calendar tools against a real Calendar.app —
the only backend `MacCalendarProviderTest` can't exercise, since its tests mock the
`runScript` seam rather than invoking real `osascript`.

## Prerequisites

- macOS with Calendar.app configured with at least one calendar (System Settings →
  Internet Accounts, or a local "On My Mac" calendar via Calendar.app → File → New
  Calendar).
- The `sophi-cli` fat jar built: `mvn -pl sophi-cli -am package -DskipTests -q`
  → `sophi-cli/target/sophi-cli-1.0.0-SNAPSHOT.jar`
- A running LLM provider (see `TESTING_SCHEDULE.md`'s Prerequisites section for the
  same Ollama/Claude setup this doesn't repeat).

## 1. List calendars

```bash
JAR="$(pwd)/sophi-cli/target/sophi-cli-1.0.0-SNAPSHOT.jar"
printf 'list my calendars\nexit\n' | java -jar "$JAR" --provider claude
```

Watch for a `⚙ list_calendars` tool call whose result names your real calendars, with
one marked `(default)`.

## 2. Create an event

```bash
printf 'create a calendar event titled "Sophi test event" tomorrow from 3pm to 4pm\nexit\n' | \
  java -jar "$JAR" --provider claude
```

Confirm in Calendar.app itself that the event now exists with the right title and time.

## 3. Read it back

```bash
printf 'what events do I have tomorrow\nexit\n' | java -jar "$JAR" --provider claude
```

Confirm the `list_calendar_events` (or `get_calendar_event`, if the model asks for the
specific id) result includes "Sophi test event".

## 4. Create a recurring event

```bash
printf 'create a calendar event titled "Sophi recurring test" every day for the next 5 days, 9am to 9:15am\nexit\n' | \
  java -jar "$JAR" --provider claude
```

Confirm in Calendar.app that the event shows a repeat indicator and the correct count.

## 5. Update it

```bash
printf 'move the Sophi test event to 5pm instead\nexit\n' | java -jar "$JAR" --provider claude
```

Confirm this required a confirmation prompt (update is `DESTRUCTIVE`) and that
Calendar.app now shows the new time.

## 6. Delete it

```bash
printf 'delete the Sophi test event\nexit\n' | java -jar "$JAR" --provider claude
```

Confirm the confirmation prompt again, and that the event is gone from Calendar.app.

## 7. Clean up the recurring test event

Since the recurring test event isn't cleaned up by the steps above, delete it manually
in Calendar.app (or ask Sophi to delete it by name) once you've confirmed it recurred
correctly.
