# Observability Kit Demo: interaction backtracking

A user reports *"I clicked this button and got an error"* (or *"...and it hung
forever"*) and Observability Kit backtracks it to a replicable interaction,
consumable by a human or an AI agent, with no external observability stack.
The insights are served by the kit's `/actuator/vaadin/observability`
endpoint; this module is a plain end-user application that only depends on
`observability-kit-starter`.

## The scenario

The app is a small orders view. Every order has a **Ship** button, with two
problems planted in `OrderShippingService`:

- shipping order **#1042** fails with a `NullPointerException` (missing
  warehouse allocation): *"I clicked Ship and got an error"*;
- shipping order **#1041** succeeds but blocks for ~3 seconds (synchronous
  call to a legacy warehouse system): *"I clicked Ship and it hung forever"*.

All other orders ship fine and instantly.

## Run the demo

Start the app:

```bash
mvn spring-boot:run -pl :observability-kit-demo
```

1. Open <http://localhost:8080/orders>.
2. Click **Ship** on order #1042: an error notification appears. Click **Ship**
   on order #1041: it works, but takes seconds. Ship another order for
   contrast.
3. Read the insights:

```bash
curl -s http://localhost:8080/actuator/vaadin/observability | jq
```

You get one insight per problem (not N stack traces in a log), for example:

```json
{
  "schemaVersion": 1,
  "insights": [
    {
      "id": "user-interaction-error",
      "severity": "error",
      "category": "reliability",
      "summary": "User interaction 'click' on Button failed with NullPointerException (2 occurrences)",
      "evidence": {
        "route": "orders",
        "component": "com.vaadin.flow.component.button.Button",
        "event": "click",
        "exception": "java.lang.NullPointerException",
        "applicationFrame": "com.vaadin.observability.demo.OrderShippingService.ship(OrderShippingService.java:36)"
      },
      "replay": [
        "Open route '/orders'",
        "Locate component Button",
        "Trigger a 'click' event on it",
        "Expect NullPointerException: ..."
      ],
      "suggestion": "Inspect com.vaadin.observability.demo.OrderShippingService.ship(...)..."
    },
    {
      "id": "slow-user-interaction",
      "severity": "warning",
      "category": "performance",
      "summary": "User interaction 'click' on Button took up to 3004 ms, over the 1000 ms UX budget (1 occurrence)",
      "evidence": {
        "route": "orders",
        "component": "com.vaadin.flow.component.button.Button",
        "event": "click",
        "maxDurationMs": 3004,
        "thresholdMs": 1000
      },
      "replay": [
        "Open route '/orders'",
        "Locate component Button",
        "Trigger a 'click' event on it",
        "Expect the UI to stay unresponsive for roughly 3004 ms"
      ],
      "suggestion": "The 'click' handler in Button blocks the request for up to 3004 ms..."
    }
  ]
}
```

4. Let an AI agent turn the insights into fixes, see below.

## Consume the insights with an AI agent

The payload is designed for agents that have access to the application
codebase: `evidence.applicationFrame` points at the exact class and line,
`replay` describes how to reproduce, and `exemplars` carry the stack and
session context. With [Claude Code](https://www.claude.com/product/claude-code)
installed, run it from the application repository (this repo for the demo) and
let it read the endpoint:

```bash
claude "Users reported: 'I clicked a button and got an error' and 'shipping
hangs forever'. Fetch http://localhost:8080/actuator/vaadin/observability,
read the insights, find the root causes in this codebase and propose fixes."
```

Claude Code fetches the JSON, follows `evidence.applicationFrame` to
`OrderShippingService`, spots the missing null check and the blocking legacy
call, and proposes the patches, including regression tests if you ask for
them.

If the endpoint is not reachable from the agent (for example, insights pulled
from a production host), pipe the JSON in instead:

```bash
curl -s https://myapp.example.com/actuator/vaadin/observability \
  | claude -p "Here are production insights from Vaadin Observability Kit. \
Diagnose the reported problems and propose fixes in this codebase."
```

The same works with any agent that can read a URL or stdin (GitHub Copilot
CLI, Gemini CLI, an MCP-enabled IDE assistant, ...); no kit-specific tooling
is required because the contract is plain, self-describing JSON.

## Where the feature lives

This module contains only the end-user app (`com.vaadin.observability.demo`).
The feature itself ships in the kit:

| Piece | Class | Module |
|---|---|---|
| Interaction capture (errors + over-budget durations) | `insights.InteractionExemplarCollector` (hooks Flow's `RpcInvocationListener`) | `observability-kit-micrometer` |
| Bounded exemplar storage | `insights.ExemplarBuffer` | `observability-kit-micrometer` |
| Insight rules + JSON contract | `insights.InsightsService` | `observability-kit-micrometer` |
| `/actuator/vaadin/observability` endpoint | `VaadinObservabilityEndpoint` | `observability-kit-starter` |

Everything is production-mode capable: it relies only on hooks that Flow
enables in production (no `ComponentTracker`, no dev tools).
