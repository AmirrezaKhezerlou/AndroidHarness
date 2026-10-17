# Interactive chat concepts

Ask the agent to “show an interactive concept in this chat” or to create a chart,
mockup, diagram, or small interactive tool. A tool-capable provider can return a
self-contained HTML/CSS/JavaScript concept through `create_artifact`.

Each concept has native Preview/Code tabs, an expanded view, version selection,
Reload, Revise, and Save HTML. Revise attaches the selected version to the
composer and starts an editable prompt without sending a message. Expanding and switching Preview/Code preserve the live page. Reload,
changing versions, changing appearance, and reopening the app start the selected
HTML revision again; arbitrary JavaScript runtime state is not saved to disk.

HTML revisions live on saved chat messages. They survive app restarts, chat
backup/restore, and forks. Backup format 2 includes artifact payloads; older
format-1 backups still import. Database migration 16 → 17 adds a nullable message
column and preserves existing history. No workspace files or servers are needed.

## Tool contract

- `create_artifact(title, html, artifact_id?)`: creates an immutable revision.
  Supply an existing ID only to revise a concept already in this chat.
- `read_artifact(artifact_id?, revision?)`: reads the latest source or an exact
  revision, or lists concepts when no ID is supplied. The engine supplies the session; the model cannot select
  another chat.
- HTML is limited to 256 KB UTF-8; titles to 80 characters. Tool output contains
  a small receipt. HTML is persisted separately from model-facing text.
- Use inline CSS/JS, SVG, canvas, data images, and local interactions. Remote
  APIs, CDN scripts, remote fonts, and server dependencies do not run in previews.

## Renderer boundary

`ArtifactWebView` is separate from the workspace browser. The trusted outer
page embeds generated HTML in an iframe with only `sandbox="allow-scripts"`;
there is no same-origin grant. Each revision also has a separate host origin.
The child document starts with a restrictive CSP. Native WebView settings block
network, file/content access, DOM storage, popups, downloads, file selection, and
permission requests. There is no JavaScript/native bridge or app asset loader.
Generated HTML cannot call agent tools. Native controls stay outside the iframe.

The renderer is destroyed when its card leaves composition. A renderer failure
leaves native Code and Reload controls available. Save HTML exports the selected
revision through Android's document picker, retaining its offline policy.

Android references: [loading local content](https://developer.android.com/develop/ui/views/layout/webapps/load-local-content),
[WebView native bridge risks](https://developer.android.com/privacy-and-security/risks/insecure-webview-native-bridges).
