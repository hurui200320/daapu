import { marked, type Tokens, type TokenizerAndRendererExtension } from 'marked'
import DOMPurify from 'dompurify'
// the common bundle registers ~40 languages (kotlin, python, java, bash,
// json, yaml, …) instead of all ~190 — a ~1MB saving in the shipped bundle;
// unregistered languages fall back to the escaped plain-text path in
// `highlight`
import hljs from 'highlight.js/lib/common'
import katex from 'katex'
import 'highlight.js/styles/github-dark.css'
import 'katex/dist/katex.min.css'

/**
 * Rendering pipeline for chat text: marked -> custom code-block chrome
 * (language label + copy button, llama.cpp webui style) -> highlight.js ->
 * KaTeX (math) -> DOMPurify. The copy button is wired by event delegation in
 * MarkdownContent.svelte.
 */

interface MathToken {
  type: 'math'
  raw: string
  text: string
  display: boolean
}

function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
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

marked.use({ renderer })

/*
 * Math via a marked inline extension, keeping the string pipeline intact:
 * `$$...$$` (multiline, display) and `$...$` (single-line, inline) are
 * tokenized before marked's own inline rules can mangle the content
 * (underscores, backslashes), and KaTeX output is sanitized by DOMPurify
 * together with the rest. `output: 'html'` avoids the MathML that DOMPurify
 * would strip.
 *
 * The opener guards live IN the tokenizer regexes (a `$`/`$$` followed by
 * space or tab — and a digit, for inline, so currency like `$5` survives —
 * is not math), because marked calls inline tokenizers on every position;
 * the `start` function only mirrors the same guards to bound the text rule
 * (it stops the text scan at the next math opener instead of scanning to
 * the end). This way the guards hold uniformly, including at message or
 * paragraph starts, where the tokenizer runs directly on a `$` opener.
 *
 * Known limitations, accepted for now: a stray `$` inside display math
 * (`$$a $ b$$`) is a KaTeX parse error rendered in KaTeX's error style, and
 * delimiters glued to prose (`foo$$x$$bar`) mis-tokenize as inline math
 * plus a stray `$`. Invalid/partial math (mid-stream chunks) renders in
 * KaTeX's error style instead of throwing.
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

export function renderMarkdown(text: string): string {
  const html = marked.parse(text, { async: false }) as string
  return DOMPurify.sanitize(html)
}
