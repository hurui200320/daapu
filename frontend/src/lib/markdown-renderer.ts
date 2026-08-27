/**
 * Lazy-loading facade over the chat-text rendering pipeline (marked ->
 * custom code-block chrome -> highlight.js -> KaTeX -> DOMPurify).
 *
 * Everything heavy lives behind a dynamic import: highlight.js (~40 common
 * languages), KaTeX and their CSS previously loaded on the home screen
 * before any message existed (a >500 kB critical chunk). Callers await
 * [getMarkdownRenderer]; the pipeline initializes exactly once and is cached
 * forever after (module-scope singleton, browser-only app).
 */
// type-only: erased at build, no runtime eager load of marked
import type { TokenizerAndRendererExtension, Tokens } from 'marked'

type RenderMarkdown = (text: string) => string

let cached: Promise<RenderMarkdown> | null = null
// the tabnabbing hook below lives on the DOMPurify module (a page-global):
// a retried init after a partial failure must not stack a second copy
let hookInstalled = false

/**
 * Resolve the render function, initializing the pipeline on first call.
 *
 * A FAILED init must not poison the singleton for the whole session (stale
 * chunk hashes after a redeploy are the common trigger): it is evicted so
 * the NEXT caller re-runs `init` — the caller that hit the failure still
 * observes the rejection and must tolerate it.
 */
export function getMarkdownRenderer(): Promise<RenderMarkdown> {
  if (!cached) {
    const attempt = init().catch((error) => {
      if (cached === attempt) cached = null
      throw error
    })
    cached = attempt
  }
  return cached
}

async function init(): Promise<RenderMarkdown> {
  // dynamic imports: these MUST stay lazy (that is the whole point). The
  // `marked` type aliases above are erased at compile time.
  const [{ marked }, DOMPurifyModule, hljsModule, katex] = await Promise.all([
    import('marked'),
    import('dompurify'),
    import('highlight.js/lib/common'),
    import('katex'),
  ])
  await Promise.all([import('highlight.js/styles/github-dark.css'), import('katex/dist/katex.min.css')])
  const DOMPurify = DOMPurifyModule.default
  // the highlight API lives on the default export, not the namespace
  const hljs = hljsModule.default

  interface MathToken {
    type: 'math'
    raw: string
    text: string
    display: boolean
  }

  function escapeHtml(text: string): string {
    return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
  }

  function highlight(text: string, lang: string): { html: string; language: string } {
    const language = lang.trim().toLowerCase()
    if (!language || !hljs.getLanguage(language)) {
      return { html: escapeHtml(text), language }
    }
    try {
      return { html: hljs.highlight(text, { language }).value, language }
    } catch {
      return { html: escapeHtml(text), language }
    }
  }

  const renderer = new marked.Renderer()

  renderer.code = ({ text, lang }) => {
    const { html, language } = highlight(text, lang ?? '')
    const label = language || 'text'
    return [
      `<div class="code-block-wrapper">`,
      `<div class="code-block-header">`,
      `<span class="code-language">${escapeHtml(label)}</span>`,
      `<button type="button" class="code-copy-btn">Copy</button>`,
      `</div>`,
      `<div class="code-block-scroll-container">`,
      `<pre><code class="hljs language-${escapeHtml(label)}">${html}</code></pre>`,
      `</div>`,
      `</div>`,
    ].join('')
  }

  renderer.link = function ({ href, title, tokens }) {
    // chat links must not navigate the app away (a plain <a> would unload the
    // SPA): open in a new tab, and never hand the new tab a window reference
    // (tabnabbing). The parser must come from `this` (regular function, not
    // arrow): marked.use copies this method onto an internal wrapper and
    // assigns `parser` only there.
    const linkText = this.parser?.parseInline(tokens) ?? ''
    const attrs = `href="${escapeHtml(href)}" target="_blank" rel="noopener noreferrer"`
    return `<a ${attrs}${title ? ` title="${escapeHtml(title)}"` : ''}>${linkText}</a>`
  }

  // DOMPurify strips `target` by default (tabnabbing), so the sanitize call
  // re-allows it — and this hook forces every surviving anchor (raw HTML
  // included) onto the safe shape above.
  if (!hookInstalled) {
    DOMPurify.addHook('afterSanitizeAttributes', (node) => {
      if (node.tagName === 'A' || node.tagName === 'AREA') {
        node.setAttribute('target', '_blank')
        node.setAttribute('rel', 'noopener noreferrer')
      }
    })
    hookInstalled = true
  }

  marked.use({ renderer })

  /*
   * Math via a marked inline extension, keeping the string pipeline intact:
   * `$$...$$` (multiline, display) and `$...$` (single-line, inline) are
   * tokenized before marked's own inline rules can mangle the content
   * (underscores, backslashes); KaTeX output is sanitized by DOMPurify
   * together with the rest. `output: 'html'` avoids the MathML DOMPurify
   * would strip.
   *
   * Opener guards live IN the tokenizer regexes (`$`/`$$` followed by space,
   * tab — and a digit, for inline, so currency like `$5` survives — is not
   * math); the `start` function mirrors them to bound the text rule.
   *
   * Known limitations, accepted: a stray `$` inside display math renders in
   * KaTeX's error style, delimiters glued to prose mis-tokenize, and invalid/
   * partial math (mid-stream chunks) renders in KaTeX's error style instead
   * of throwing.
   */
  const mathExtension: TokenizerAndRendererExtension = {
    name: 'math',
    level: 'inline',
    start(src: string): number | undefined {
      const m = src.match(/(?<!\\)\$\$(?![ \t])|(?<!\\)\$(?![ \t\d])/)
      return m ? m.index : undefined
    },
    tokenizer(src: string): MathToken | undefined {
      let m: RegExpMatchArray | null
      if ((m = src.match(/^\$\$(?![ \t])([\s\S]+?)\$\$/))) {
        return { type: 'math', raw: m[0], text: m[1], display: true }
      }
      if ((m = src.match(/^\$(?![ \t\d])((?:\\.|[^\\$\n])+)\$/))) {
        return { type: 'math', raw: m[0], text: m[1], display: false }
      }
      return undefined
    },
    renderer(token: Tokens.Generic): string {
      const math = token as MathToken
      return katex.renderToString(math.text, {
        displayMode: math.display,
        throwOnError: false,
        output: 'html',
        strict: 'ignore',
      })
    },
  }

  marked.use({ extensions: [mathExtension] })

  return (text: string): string => {
    const html = marked.parse(text, { async: false }) as string
    return DOMPurify.sanitize(html, { ADD_ATTR: ['target'] })
  }
}
