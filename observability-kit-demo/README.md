# Observability Kit Demo: error backtracking

A user reports *"I clicked this button and got an error"* and Observability Kit
backtracks it to a replicable interaction, consumable by a human or an AI
agent, with no external observability stack. The insights are served by the
kit's `/actuator/vaadin/observability` endpoint; this module is a plain
end-user application that only depends on `observability-kit-starter`.

## The scenario

The app is a small orders view. Every order has a **Ship** button. Shipping
order **#1042** fails with a `NullPointerException` planted in
`OrderShippingService` (a missing warehouse allocation). All other orders ship
fine. The user report is just: *"I clicked Ship and got an error."*

## Run the demo

Start the app:

```bash
mvn spring-boot:run -pl :observability-kit-demo
```

1. Open <http://localhost:8080/orders>.
2. Click **Ship** on order #1042: an error notification appears. Click it a
   couple of times, and ship another order successfully for contrast.
3. Read the insight:

```bash
curl -s http://localhost:8080/actuator/vaadin/observability | jq
```

You get one insight (not N stack traces in a log), for example:

```json
{
  "schemaVersion": 1,
  "insights": [
    {
      "id": "user-interaction-error",
      "severity": "error",
      "summary": "User interaction 'click' on NativeButton failed with NullPointerException (2 occurrences)",
      "evidence": {
        "route": "orders",
        "component": "com.vaadin.flow.component.html.NativeButton",
        "event": "click",
        "exception": "java.lang.NullPointerException",
        "applicationFrame": "com.vaadin.observability.demo.OrderShippingService.ship(OrderShippingService.java:30)"
      },
      "replay": [
        "Open route '/orders'",
        "Locate component NativeButton",
        "Trigger a 'click' event on it",
        "Expect NullPointerException: ..."
      ],
      "suggestion": "Inspect com.vaadin.observability.demo.OrderShippingService.ship(...)..."
    }
  ]
}
```

4. Let an AI agent turn the insight into a fix, see below.

## Consume the insights with an AI agent

The payload is designed for agents that have access to the application
codebase: `evidence.applicationFrame` points at the exact class and line,
`replay` describes how to reproduce, and `exemplars` carry the stack and
session context. With [Claude Code](https://www.claude.com/product/claude-code)
installed, run it from the application repository (this repo for the demo) and
let it read the endpoint:

```bash
claude "A user reported: 'I clicked a button and got an error'. Fetch
http://localhost:8080/actuator/vaadin/observability, read the insights,
find the root cause in this codebase and propose a fix."
```

Claude Code fetches the JSON, follows `evidence.applicationFrame` to
`OrderShippingService.ship`, spots the missing null check and proposes the
patch, including a regression test if you ask for one.

If the endpoint is not reachable from the agent (for example, insights pulled
from a production host), pipe the JSON in instead:

```bash
curl -s https://myapp.example.com/actuator/vaadin/observability \
  | claude -p "Here are production insights from Vaadin Observability Kit. \
Diagnose the reported errors and propose fixes in this codebase."
```

The same works with any agent that can read a URL or stdin (GitHub Copilot
CLI, Gemini CLI, an MCP-enabled IDE assistant, ...); no kit-specific tooling
is required because the contract is plain, self-describing JSON.

## Where the feature lives

This module contains only the end-user app (`com.vaadin.observability.demo`).
The feature itself ships in the kit:

| Piece | Class | Module |
|---|---|---|
| Failed-interaction capture | `insights.ErrorBacktrackCollector` (hooks Flow's `RpcInvocationListener`) | `observability-kit-micrometer` |
| Bounded exemplar storage | `insights.ErrorExemplarBuffer` | `observability-kit-micrometer` |
| Grouping + JSON contract | `insights.InsightsService` | `observability-kit-micrometer` |
| `/actuator/vaadin/observability` endpoint | `VaadinObservabilityEndpoint` | `observability-kit-starter` |

Everything is production-mode capable: it relies only on hooks that Flow
enables in production (no `ComponentTracker`, no dev tools).
