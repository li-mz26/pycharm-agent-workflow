# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

A PyCharm plugin that generates DAG workflows through natural language conversation with an LLM agent. Provides a visual workflow editor (Swing/Java2D canvas) similar to Dify, with an embedded MCP server for external AI agent integration.

## Commands

```bash
# Run the plugin in a development IDE instance
./gradlew runIde

# Build the plugin
./gradlew build

# Run tests
./gradlew test

# Run a single test class
./gradlew test --tests "com.limz26.workflow.model.WorkflowModelTest"

# Package the plugin as a fat-jar (embeds all runtime deps)
./gradlew jar
```

## Architecture

Main packages under `src/main/kotlin/com/limz26/workflow/`:

- **`model/`** - Two parallel hierarchies (see below) + serialization, loading, exporting
- **`agent/`** - Keyword-intent-based conversational agent wrapping `LLMClient`
- **`llm/`** - HTTP LLM client (OpenAI-compatible, raw `HttpURLConnection`)
- **`mcp/`** - Embedded MCP server (Ktor CIO + `io.modelcontextprotocol:kotlin-sdk-server`)
- **`ui/`** - Tool window, main panel with split chat/canvas, `WorkflowCanvas` (Java2D)
- **`settings/`** - `AppSettings` persistent state via IntelliJ `PersistentStateComponent`
- **`actions/`** - IDE actions registered in `plugin.xml`
- **`util/`** - `WorkflowDetector` scans project for workflow folders

## Dual Model Hierarchy (critical to understand)

There are two parallel object graphs that represent workflows:

1. **Runtime model** (`Workflow.kt`): `Workflow` / `WorkflowNode` / `WorkflowEdge` — uses `NodeType` enum, hand-rolled `toJson()` via string interpolation. Used by `WorkflowAgent` and returned from LLM parsing.

2. **Disk/Gson model** (`WorkflowLoader.kt`): `WorkflowDefinition` / `NodeDefinition` — uses `String` for type, supports `codeFile`/`promptFile` fields for external file references. Used by `WorkflowLoader`, `WorkflowExporter`, `WorkflowMcpService`, and `WorkflowCanvas`.

Conversion bridges (`toWorkflow()`, `convertToWorkflow()`, `convertToDefinition()`) are scattered across `WorkflowPanel`, `WorkflowCanvas`, and `WorkflowMcpService`. When editing either model, check which one the consuming code expects.

## Two JSON Libraries

- **`kotlinx.serialization.json`**: used in `WorkflowAgent.parseWorkflowFromJson()` and `LLMClient.extractJsonFromText()`
- **`Gson`**: used in `WorkflowLoader`, `WorkflowExporter`, `WorkflowMcpService`

This is intentional (historical) but flagged for future cleanup. Do not consolidate unless specifically tasked.

## Workflow Format on Disk

```
project/
└── workflows/
    └── my_workflow/
        ├── workflow.json          # DAG (uses codeFile/promptFile refs)
        └── nodes/
            ├── {node_id}.py       # code node Python (main(inputs) function)
            ├── {node_id}_prompt.md
            └── {node_id}_config.json
```

Node types: `start`, `end`, `condition`, `code`, `agent`, `http`, `variable`

## LLM Client

Provider routing in `LLMClient.chat()` matches the configured `apiEndpoint` string:
- Contains `"openai"` → fully implemented OpenAI path
- Contains `"anthropic"` or `"moonshot"` → stubs (fall through to generic → OpenAI)
- Else → generic (calls OpenAI path)

`buildChatCompletionsEndpoint()` normalizes user-supplied endpoints: adds `http://` for localhost/private IPs, `https://` otherwise, appends `/v1/chat/completions` if missing.

## MCP Server

`WorkflowMcpService` is an IntelliJ `@Service` that runs an embedded Ktor CIO server. Exposes 5 tools at `http://0.0.0.0:{port}/mcp`:

| Tool | Key args |
|---|---|
| `workflows_list` | `projectBasePath` |
| `workflow_read_json` | `workflowDirPath` |
| `workflow_read_node_code` | `workflowDirPath`, `nodeId` |
| `workflow_edit` | `workflowDirPath`, `request` (JSON of `WorkflowEditRequest`) |
| `workflow_run` | `workflowDirPath` (validates + simulates, no real execution) |

The port defaults to `8765` (configurable in settings). Toggle button in the canvas toolbar starts/stops the server at runtime.

## Fat-Jar Build

The `jar` Gradle task embeds all runtime dependencies (Ktor, MCP SDK) into the plugin `.jar`. This is required for single-file plugin installs. `PrepareSandboxTask` also copies them into the sandbox `lib/` for `runIde`. When adding new runtime dependencies, ensure they are on `runtimeClasspath` (not `compileOnly`).

## Agent Logic

`WorkflowAgent.talk()` uses keyword matching (`looksLikeWorkflowIntent()`) to decide between:
1. **Workflow generation**: calls `LLMClient.generateWorkflowDSL()`, parses JSON response, validates graph (DFS cycle detection + isolation check), returns `AgentResponse(reply, workflow)`
2. **General conversation**: calls `LLMClient.chat()` with a Chinese ReAct-style system prompt

`modifyWorkflow()` is a **stub** (not implemented). Incremental editing via conversation is not yet functional.

## Testing Gotchas

Tests are in `src/test/kotlin/` using JUnit 4. `WorkflowAgent` and `LLMClient` use `service<AppSettings>()` via lazy delegation, which requires the IntelliJ platform to be running. `WorkflowAgentTest` explicitly catches `NullPointerException` from this — don't treat it as a bug.

`LLMClientExtractJsonTest` tests `extractJsonFromText()` (declared `internal` in the companion object) directly and is reliable.

## Known Issues

- **P0**: `createErrorWorkflow()` generates nodes with random UUIDs but hardcodes edge endpoints as `"node_1"`, `"node_2"`, `"node_3"` — the error workflow topology is invalid.
- **P1**: `Workflow.toJson()` emits `position: {x, y}` but `parseWorkflowFromJson` reads both `position.x` and root-level `x/y` for compatibility — format inconsistency.
- **P1**: `modifyWorkflow()` unimplemented.
- **P2**: `GenerateWorkflowFromSelectionAction` does not pass selected text to the panel (TODO in code).
