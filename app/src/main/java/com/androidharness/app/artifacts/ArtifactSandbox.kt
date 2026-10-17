package com.androidharness.app.artifacts

/** Only the trusted outer document owns the frame. Generated code always has an opaque origin. */
object ArtifactSandbox {
    const val CSP = "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data: blob:; font-src data:; media-src data: blob:; connect-src 'none'; frame-src 'none'; object-src 'none'; worker-src 'none'; base-uri 'none'; form-action 'none'"

    fun sourceDocument(artifact: ChatArtifact, dark: Boolean): String {
        artifact.validated()
        val bg = if (dark) "#141517" else "#fcfcfd"
        val fg = if (dark) "#eceef2" else "#22252b"
        val muted = if (dark) "#adb2bc" else "#626773"
        val panel = if (dark) "#202226" else "#f1f2f5"
        val border = if (dark) "#33363d" else "#e2e4e9"
        val accent = if (dark) "#b3bfff" else "#435ccd"
        return """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta http-equiv="Content-Security-Policy" content="$CSP"><style>
            :root{color-scheme:${if (dark) "dark" else "light"};--background:$bg;--foreground:$fg;--muted:$muted;--surface:$panel;--border:$border;--accent:$accent;font:14px/1.55 system-ui,sans-serif;background:$bg;color:$fg}
            *{box-sizing:border-box}body{margin:0;padding:16px;overflow-wrap:anywhere}img,svg,canvas,video{max-width:100%}button,input,select,textarea{font:inherit}button{min-height:44px;padding:8px 14px;border:1px solid var(--border);border-radius:12px;background:var(--surface);color:var(--foreground)}input,select,textarea{max-width:100%;min-height:44px}button:focus-visible,input:focus-visible{outline:2px solid var(--accent);outline-offset:2px}@media(prefers-reduced-motion:reduce){*,*::before,*::after{animation:none!important;transition:none!important}}
            </style></head><body>${artifact.html}</body></html>""".trimIndent()
    }

    fun frameDocument(artifact: ChatArtifact, dark: Boolean): String =
        """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><style>html,body{margin:0;width:100%;height:100%;overflow:hidden;background:${if (dark) "#141517" else "#fcfcfd"}}iframe{width:100%;height:100%;border:0;display:block}</style></head><body><iframe title="${escape(artifact.title)}" sandbox="allow-scripts" referrerpolicy="no-referrer" srcdoc="${escape(sourceDocument(artifact, dark))}"></iframe></body></html>"""

    fun origin(artifact: ChatArtifact) = "https://concept-${artifact.id}-${artifact.revision}.invalid/"

    private fun escape(value: String) = value.replace("&", "&amp;").replace("\"", "&quot;")
        .replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;")
}
