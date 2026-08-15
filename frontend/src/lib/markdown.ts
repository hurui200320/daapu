import { marked } from 'marked'
import DOMPurify from 'dompurify'
import hljs from 'highlight.js'
import 'highlight.js/styles/github-dark.css'

/**
 * Rendering pipeline for chat text: marked -> custom code-block chrome
 * (language label + copy button, llama.cpp webui style) -> highlight.js ->
 * DOMPurify. The copy button is wired by event delegation in
 * MarkdownContent.svelte.
 */

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

export function renderMarkdown(text: string): string {
  const html = marked.parse(text, { async: false }) as string
  return DOMPurify.sanitize(html)
}
