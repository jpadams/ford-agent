# ford-agent

A small Spring Boot service that exposes a chat endpoint backed by an OpenAI model. The model can answer questions about data in a Neo4j graph by calling Cypher tools via function calling. Multi-turn conversations are preserved across requests using Spring AI's JDBC chat-memory repository against an in-memory HSQLDB database, with browser sessions auto-binding to a conversation id.

---

## Architecture at a glance

```
   HTTP client (browser / curl)
            │
            ▼ POST /chat   { message, conversationId? }
   ┌──────────────────────┐
   │  ChatController       │  resolves conversationId from request body
   │                       │  or HttpSession (JSESSIONID cookie)
   └──────────┬────────────┘
              │ ChatClient.prompt().user(...)
              ▼
   ┌──────────────────────────────────────────────┐
   │ Spring AI ChatClient                          │
   │   • MessageChatMemoryAdvisor                  │  loads/saves history
   │   • default system prompt                     │
   │   • default tools (Neo4jTools)                │
   └──┬─────────────────────────┬─────────────────┘
      │                         │
      ▼                         ▼
   OpenAI chat API         Tool dispatch (when model decides)
   (gpt-4o-mini)              │
                              ▼
                          ┌──────────────────┐
                          │ Neo4jTools        │   getSchema()
                          │                   │   runReadQuery(cypher, params)
                          └────────┬─────────┘
                                   │
                                   ▼
                              Neo4j driver
                              (Aura or local)

   Memory store: HSQLDB in-memory, table SPRING_AI_CHAT_MEMORY,
   keyed by conversation_id. Schema auto-applied at startup by
   spring-ai-starter-model-chat-memory-repository-jdbc.
```

### Request flow, step by step
1. Client POSTs `{ message, conversationId? }` to `/chat`.
2. `ChatController` figures out the conversation id:
   - body-supplied id wins (and is stored on the session for next time);
   - else session attribute `conversationId`;
   - else mint a new UUID and stash it on the session.
3. `ChatClient.prompt().user(message).advisors(... CONVERSATION_ID ...).call()` runs the chain:
   - `MessageChatMemoryAdvisor` reads the latest `maxMessages` rows from HSQLDB for that conversation, prepends them to the prompt.
   - `OpenAiChatModel` calls the OpenAI API with the system prompt, prior messages, the new user message, and the tool catalog.
   - If the model emits a tool call, Spring AI invokes the matching `@Tool` method on `Neo4jTools`, feeds the result back, and loops until the model returns plain text.
   - The advisor writes the new user + assistant (+ tool) messages to HSQLDB.
4. Response `{ conversationId, reply }` returns to the client. Browser keeps a `JSESSIONID` cookie that ties subsequent calls to the same conversation.

---

## Project layout

```
ford-agent/
  build.gradle.kts
  settings.gradle.kts
  .gitignore
  src/main/java/com/example/fordagent/
    FordAgentApplication.java           # @SpringBootApplication entry point
    config/
      ChatClientConfig.java             # ChatMemory + ChatClient beans, system prompt
      Neo4jConfig.java                  # Driver bean from neo4j.* properties
      WebConfig.java                    # forwards /ui to static ui.html
    chat/
      ChatController.java               # POST /chat, GET /chat, POST /chat/new
      ChatRequest.java                  # record { conversationId, message }
      ChatResponse.java                 # record { conversationId, reply }
      HistoryMessage.java               # record { role, content }
      HistoryResponse.java              # record { conversationId, messages }
    tools/
      Neo4jTools.java                   # @Tool methods exposed to the LLM
  src/main/resources/
    application.yml
    static/
      ui.html                           # single-page vanilla-JS chat UI
```

---

## Stack

| Layer            | Choice |
|------------------|--------|
| JVM              | Java 21 (Temurin) |
| Build            | Gradle Kotlin DSL |
| Web              | Spring Boot 3.4 / Tomcat |
| LLM              | OpenAI via `spring-ai-starter-model-openai` |
| Chat memory      | `spring-ai-starter-model-chat-memory-repository-jdbc` + `MessageWindowChatMemory` |
| Memory storage   | HSQLDB in-memory (`jdbc:hsqldb:mem:chatmem`) |
| Graph DB         | Neo4j via `neo4j-java-driver` (Aura or self-hosted) |

Spring AI 1.0.0 ships JDBC chat-memory schemas for postgresql, sqlserver, hsqldb, and mariadb — **not** H2. That's why HSQLDB is the embedded store here.

---

## Prerequisites

Install once:

```bash
brew install --cask temurin@21
brew install gradle      # only needed to generate ./gradlew once
```

You also need:
- An **OpenAI API key** (https://platform.openai.com/api-keys).
- A reachable **Neo4j** instance. Aura works (use `neo4j+s://...`); a local container also works:
  ```bash
  docker run -d --name neo4j -p 7474:7474 -p 7687:7687 \
    -e NEO4J_AUTH=neo4j/password neo4j:5
  ```

---

## Build & run

```bash
# one-time, generates ./gradlew
gradle wrapper

# set credentials in the shell that runs the app
export OPENAI_API_KEY=sk-...
export NEO4J_URI=neo4j+s://<your-aura-id>.databases.neo4j.io     # or bolt://localhost:7687
export NEO4J_USERNAME=neo4j                                       # or your Aura user
export NEO4J_PASSWORD=...

./gradlew bootRun
```

The app listens on `http://localhost:8080`. There is no GET root.

### Web UI

Open **http://localhost:8080/ui** in a browser. It's a single static page (vanilla HTML/JS, no build step) that talks to the same `/chat` endpoints:

- Shows the current `conversationId` in the header.
- Loads existing history on page load (so reloading the browser brings back your conversation as long as the `JSESSIONID` cookie is still valid and the JVM hasn't restarted).
- Textarea + **Submit** button. Press Enter to send, Shift+Enter for a newline.
- **New convo** button clears the current conversation's messages from chat memory, mints a fresh `conversationId`, and resets the view.

The page is served from `src/main/resources/static/ui.html`; the path `/ui` is mapped to it via a forward in `config/WebConfig.java`.

### HTTP API

Endpoints:

| Method & path     | Purpose |
|-------------------|---------|
| `POST /chat`      | Send a message. Body: `{ message, conversationId? }`. Returns `{ conversationId, reply }`. |
| `GET /chat`       | Return history for the session's conversation: `{ conversationId, messages: [{role, content}] }`. Only user/assistant turns. |
| `POST /chat/new`  | Clear the current conversation from chat memory and start a fresh one. Returns `{ conversationId, messages: [] }`. |
| `GET /ui`         | The web UI (forwarded to `ui.html`). |

Single-turn (server mints a new conversation id, returns it):

```bash
curl -s -X POST localhost:8080/chat \
  -H 'content-type: application/json' \
  -d '{"message":"What labels exist in the graph?"}' | jq
```

Multi-turn using a cookie jar (session-bound, no need to echo the id):

```bash
rm -f cookies.txt
curl -s -b cookies.txt -c cookies.txt -X POST localhost:8080/chat \
  -H 'content-type: application/json' \
  -d '{"message":"List Tom Hanks movies"}' | jq .reply

curl -s -b cookies.txt -c cookies.txt -X POST localhost:8080/chat \
  -H 'content-type: application/json' \
  -d '{"message":"What was his 2006 movie?"}' | jq .reply
```

Multi-turn by explicit id (works for non-browser clients):

```bash
CID=$(curl -s -X POST localhost:8080/chat -H 'content-type: application/json' \
       -d '{"message":"hi"}' | jq -r .conversationId)
curl -s -X POST localhost:8080/chat -H 'content-type: application/json' \
  -d "{\"conversationId\":\"$CID\",\"message\":\"and again\"}"
```

---

## What you can tweak

### LLM model and options — `application.yml`
```yaml
spring:
  ai:
    openai:
      chat:
        options:
          model: gpt-4o-mini       # any OpenAI chat model id
          temperature: 0.2         # add other ChatOptions here as needed
          max-tokens: 1024
```

### System prompt — `config/ChatClientConfig.java`
`SYSTEM_PROMPT` is the constant injected via `.defaultSystem(...)`. Edit it to change the assistant's persona, constraints, or how aggressively it should consult the tools.

### Memory window — `config/ChatClientConfig.java`
```java
MessageWindowChatMemory.builder()
    .chatMemoryRepository(repo)
    .maxMessages(20)   // last N messages loaded into the prompt
    .build();
```
Only the last `maxMessages` messages (user + assistant + tool combined) are sent to the model. Older turns stay in the DB but are not used. Raise this for longer "memory horizon", lower it to trim cost.

### Session timeout — `application.yml`
Browser sessions default to 30 min of idleness. To change:
```yaml
server:
  servlet:
    session:
      timeout: 8h     # examples: 30m, 8h, 7d
```
Note: a fresh session after timeout will mint a new `conversationId`; older rows remain in HSQLDB until JVM restart.

### Memory storage — `application.yml` + `build.gradle.kts`
- **Pure in-memory (default):** `jdbc:hsqldb:mem:chatmem` — fast, zero infra, lost on restart.
- **File-backed HSQLDB:** `jdbc:hsqldb:file:./data/chatmem;shutdown=true` — survives restart, no extra dependency.
- **Postgres:** swap `org.hsqldb:hsqldb` for `org.postgresql:postgresql`, set `spring.datasource.url=jdbc:postgresql://...`, and the JDBC chat-memory starter will use its bundled `schema-postgresql.sql` automatically.

### Neo4j connectivity — `application.yml` / env vars
- For Aura, the URI scheme **must be `neo4j+s://`** (TLS).
- For a Neo4j cluster, use `neo4j://` to enable routing.
- Plain bolt for single-instance dev: `bolt://localhost:7687`.

### Row cap on Cypher results — `tools/Neo4jTools.java`
```java
private static final int MAX_ROWS = 50;
```
Rows beyond this are truncated and the tool tells the model "(truncated at 50)". Raise for richer answers, lower to cap token usage.

### Adding more tools
Any Spring bean method annotated with `@Tool` and registered via `.defaultTools(...)` in `ChatClientConfig` becomes callable by the model. Pattern:
```java
@Tool(description = "Clear, model-facing description of when to call this and what it returns.")
public String myTool(@ToolParam(description = "...") String arg) { ... }
```
Keep descriptions concrete — they're the model's only documentation.

---

## Limits & retention summary

| Concern                       | Where it's set                              | Current value |
|-------------------------------|---------------------------------------------|---------------|
| Messages sent to model        | `MessageWindowChatMemory.maxMessages`       | last 20       |
| Per-message size              | DB column `LONGVARCHAR`                     | effectively unlimited |
| LLM context window            | OpenAI model                                | ~128K tokens (gpt-4o-mini) |
| Session idle timeout          | Tomcat `server.servlet.session.timeout`     | 30 minutes (default) |
| History retention             | HSQLDB `jdbc:hsqldb:mem:`                   | lost on app restart |
| Cleanup of old conversations  | none                                        | DB grows monotonically until restart |
| Cypher rows returned per call | `Neo4jTools.MAX_ROWS`                       | 50            |

---

## Verifying things work

1. Start the app, then hit `/chat` with a question that requires the schema:
   ```bash
   curl -s -X POST localhost:8080/chat -H 'content-type: application/json' \
     -d '{"message":"What labels exist in the graph?"}' | jq
   ```
   The model should call `getSchema` and reply with the labels.
2. Follow-up using the cookie jar (above) to confirm memory.
3. Ask the model to "create a node" — the read-only session in `runReadQuery` will reject it and the assistant will decline.

If something misbehaves, check the bootRun terminal: `Neo4jTools` logs the full stack trace from any tool call that fails.

---

## Troubleshooting

**`No schema scripts found at location 'classpath:.../schema-h2.sql'` on startup**
Spring AI 1.0.0 doesn't ship an H2 schema. Stay on HSQLDB (default), or switch to one of postgresql / sqlserver / hsqldb / mariadb.

**`Cannot coerce NULL to Java List` in `getSchema`**
Older versions of `Neo4jTools.java` called `.asList()` on a value that can be NULL (relationship types with no properties). The current code uses an `asListSafe(Value)` helper — make sure that helper is present.

**Model says it "can't retrieve the schema"**
The tool returned an error; the model paraphrased it. Look in the bootRun terminal for `ERROR ... Neo4jTools` — the real exception is there. Common causes: wrong URI scheme for Aura (must be `neo4j+s://`), wrong creds, custom DB user lacking permission to call `db.schema.*`.

**Whitelabel 404 on `http://localhost:8080/`**
There's no GET root. Use `/ui` for the web UI, or hit the `/chat` endpoints directly.

**Conversation doesn't seem to remember anything**
Each request that omits `conversationId` and isn't carrying a `JSESSIONID` cookie gets a fresh conversation. Use `curl -b cookies.txt -c cookies.txt`, or echo the `conversationId` from the previous response back in the body.

---

## Deliberately out of scope

- Authentication / authorisation on `/chat`
- Rate limiting
- Streaming responses (would use `.stream().content()` instead of `.call().content()`)
- Vector / RAG retrieval against Neo4j (this build is tool-calling only)
- Background job to expire old conversations
- Per-user memory (currently per-`HttpSession`; multiple humans sharing a browser session share history)
