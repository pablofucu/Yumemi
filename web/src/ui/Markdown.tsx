import { isValidElement, memo, useState } from 'react'
import type { ReactNode } from 'react'
import ReactMarkdown from 'react-markdown'
import type { Components } from 'react-markdown'
import rehypeHighlight from 'rehype-highlight'
import remarkGfm from 'remark-gfm'
import { copyText } from './clipboard'
import { IconCheck, IconCopy } from './icons'

function flatten(node: ReactNode): string {
  if (node === null || node === undefined || typeof node === 'boolean') return ''
  if (typeof node === 'string' || typeof node === 'number') return String(node)
  if (Array.isArray(node)) return node.map(flatten).join('')
  if (isValidElement(node)) return flatten((node.props as { children?: ReactNode }).children)
  return ''
}

function languageOf(node: ReactNode): string {
  if (isValidElement(node)) {
    const cls = (node.props as { className?: string }).className ?? ''
    const m = /language-([\w+-]+)/.exec(cls)
    if (m) return m[1]
  }
  return ''
}

function CodeBlock({ children }: { children?: ReactNode }) {
  const [copied, setCopied] = useState(false)
  const lang = languageOf(children)
  return (
    <div className="codeblock">
      <div className="codeblock-bar">
        <span>{lang}</span>
        <button
          type="button"
          className="icon-btn small"
          aria-label="Copiar código"
          onClick={async () => {
            if (await copyText(flatten(children).replace(/\n$/, ''))) {
              setCopied(true)
              setTimeout(() => setCopied(false), 1500)
            }
          }}
        >
          {copied ? <IconCheck size={14} /> : <IconCopy size={14} />}
        </button>
      </div>
      <pre>{children}</pre>
    </div>
  )
}

const components: Components = {
  pre: ({ children }) => <CodeBlock>{children}</CodeBlock>,
  a: ({ href, children }) => (
    <a href={href} target="_blank" rel="noopener noreferrer nofollow">
      {children}
    </a>
  ),
  // Images are not rendered (no third-party requests from the page): show them as links.
  img: ({ src, alt }) =>
    typeof src === 'string' ? (
      <a href={src} target="_blank" rel="noopener noreferrer nofollow">
        {alt || src}
      </a>
    ) : null,
  table: ({ children }) => (
    <div className="table-wrap">
      <table>{children}</table>
    </div>
  ),
}

/** GitHub-flavored Markdown with highlighted code. Raw HTML is never rendered. */
export const Markdown = memo(function Markdown({ text }: { text: string }) {
  return (
    <div className="md">
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        rehypePlugins={[[rehypeHighlight, { detect: false, ignoreMissing: true }]]}
        components={components}
      >
        {text}
      </ReactMarkdown>
    </div>
  )
})
