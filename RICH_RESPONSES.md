# Rich conversation responses

Rich responses are part of assistant messages. A reply can alternate normal prose,
tables or code with native cards, callouts, metrics, charts, comparisons,
checklists, expandable explanations, link cards and custom interactive previews.
The model chooses when the extra presentation is useful; ordinary answers stay simple.

Try “Compare these approaches with selectable cards,” “Explain this with an
interactive diagram,” or “Show the supplied numbers as a chart in your reply.”
The existing versioned/exportable artifact feature remains available separately.

## Conversation and persistence

A provider emits a complete JSON object inside a `harness-ui` code fence, between
ordinary paragraphs. The provider-neutral message text is the source of truth:
chat history, forks and existing chat backups retain these blocks without a new
database schema. Copy and search show readable content rather than the protocol.
An incomplete streamed block shows a small preparing indicator and is never
executed. Invalid content has a text-version recovery action. Literal protocol
examples can be wrapped in a longer code fence.

The supported component shapes and examples live in `RichResponse.INSTRUCTIONS`,
which the engine supplies when agent context is enabled. Plain local chat with
agent context disabled does not receive these instructions. Generation depends
on the model following the format. Native layouts support shallow composition
with `column` and responsive `columns`. Payload size, nesting, item counts,
numbers, links and text lengths are validated before rendering.

Selection/chart/checklist/disclosure state uses native saved composition state.
It survives normal recomposition and activity recreation while the chat state is
retained. The reply's original data persists on disk; arbitrary local widget edits
are presentation state, not edits to the saved answer or external systems.

Card actions, Discuss selection, Continue from here, and Ask about this **prepare
a short draft in the normal composer**. Relevant selections stay in a removable
Response context chip, and an existing draft is preserved. The context is saved
on the outgoing message while copy, editing and queue previews show the user
text. The user reviews and sends it. No response widget can
silently send a chat, execute a tool, or modify workspace files. A follow-up can
produce an updated visual in the next reply.

## Custom interactive previews

`{"type":"preview","title":"...","html":"..."}` renders a self-contained
HTML/CSS/JS visual directly in the answer. It has a small title/expand control and
an optional Ask about this action; no artifact version, Code or export toolbar.
The frame grows to fit content up to a bounded inline height. Larger previews can
expand. Light/dark appearance follows the chat, and system text scaling applies.

The existing isolated `ArtifactWebView` renderer still blocks network, file and
content access, storage, native bridges, popups, downloads and permissions. The
generated document remains in a script-only iframe with an opaque origin and
restrictive CSP. Existing artifact rendering defaults are retained.

An optional response-layout channel accepts **only** bounded height and text
context. The outer document validates the iframe message source; native code
validates and clamps the metadata again. The channel cannot call tools or navigate.
A preview can supply current input values for a user-initiated follow-up:

```javascript
parent.postMessage({ type: 'harness:state', summary: 'Duration: 15 minutes' }, '*');
```

Runtime JavaScript state remains local to the live renderer. Expansion preserves
it; reloading, reopening or changing appearance starts the preview again. It is
not an external application or a persistent data store.
