import ReactMarkdown from 'react-markdown';
import gfm from 'remark-gfm';
import {
  memo,
  useEffect,
  useMemo,
  useRef,
  useState,
  isValidElement,
  type MouseEvent,
  type ReactNode,
} from 'react';
import { Empty } from 'antd';
import classNames from 'classnames';
import { CopyIcon, DownloadIcon } from 'lucide-react';
import { usePanelContext } from './PanelProvider';
import mermaid from 'mermaid';
import {
  CodeBlock as ShadcnCodeBlock,
  CodeBlockCopyButton,
} from '@/components/ai-elements/code-block';
import { normalizeMarkdownForDisplay, type MarkdownNormalizationScope } from '@/utils/markdown';
import type { BundledLanguage } from 'shiki/bundle/web';
import { bundledLanguages } from 'shiki/bundle/web';

const MAX_RENDER_CHARS = 30_000;

type HeadingItem = {
  depth: 1 | 2 | 3;
  text: string;
  id: string;
};

const Mermaid: ReactorType.FC = (props) => {
  const { children } = props;
  const ref = useRef(null);
  useEffect(() => {
    if (ref.current) {
      mermaid.contentLoaded();
    }
  }, [children]);
  return (
    <div className="mermaid" ref={ref}>
      {children}
    </div>
  );
};

const CodeBlock: ReactorType.FC<{
  inline?: boolean;
}> = ({ inline, className, children }) => {
  const match = /language-(\w+)/.exec(className || '');

  if (match?.[1] === 'mermaid') {
    return <Mermaid>{children}</Mermaid>;
  }

  if (!inline && match) {
    const rawLang = match[1];
    const safeLanguage = (rawLang in bundledLanguages ? rawLang : 'text') as BundledLanguage;
    const codeString = Array.isArray(children)
      ? children.join('')
      : typeof children === 'string'
        ? children
        : String(children);

    return (
      <ShadcnCodeBlock code={codeString.trim()} language={safeLanguage}>
        <CodeBlockCopyButton />
      </ShadcnCodeBlock>
    );
  }

  return <code className={className}>{children}</code>;
};

function textFromChildren(children: ReactNode): string {
  if (typeof children === 'string' || typeof children === 'number') return String(children);
  if (Array.isArray(children)) return children.map(textFromChildren).join('');
  if (isValidElement<{ children?: ReactNode }>(children)) {
    return textFromChildren(children.props.children);
  }
  return '';
}

function cleanHeadingText(value: string) {
  return value
    .replace(/\[([^\]]+)\]\([^)]+\)/g, '$1')
    .replace(/[`*_~#]/g, '')
    .trim();
}

function uniqueSlug(text: string, counts: Map<string, number>) {
  const base =
    cleanHeadingText(text)
      .toLowerCase()
      .replace(/[^\p{Letter}\p{Number}]+/gu, '-')
      .replace(/^-+|-+$/g, '') || 'section';
  const count = counts.get(base) || 0;
  counts.set(base, count + 1);
  return count ? `${base}-${count + 1}` : base;
}

function parseHeadings(content: string): HeadingItem[] {
  const counts = new Map<string, number>();
  let inFence = false;

  return content
    .split('\n')
    .flatMap((line) => {
      if (/^\s*```/.test(line)) {
        inFence = !inFence;
        return [];
      }
      if (inFence) return [];

      const match = /^(#{1,3})\s+(.+?)\s*#*\s*$/.exec(line);
      if (!match) return [];

      const text = cleanHeadingText(match[2]);
      if (!text) return [];
      return [{ depth: match[1].length as 1 | 2 | 3, text, id: uniqueSlug(text, counts) }];
    });
}

function linkCitations(content: string) {
  return content.replace(/\[(S\d+)\](?!\()/gi, (_match, sourceId: string) => {
    const id = sourceId.toLowerCase();
    return `[\\[${sourceId}\\]](#source-${id})`;
  });
}

export function resolveScrollLockState(params: {
  currentScrollTop: number;
  previousScrollTop: number;
  scrollable: number;
  locked: boolean;
}) {
  if (params.currentScrollTop < params.previousScrollTop - 4) return true;
  if (params.scrollable - params.currentScrollTop < 24) return false;
  return params.locked;
}

const MarkdownRenderer: ReactorType.FC<{
  markDownContent?: string;
  isStreaming?: boolean;
  normalizationScope?: MarkdownNormalizationScope;
}> = (props) => {
  const { markDownContent, className, isStreaming = false, normalizationScope = 'default' } = props;
  const normalizedContent = normalizeMarkdownForDisplay(markDownContent, {
    scope: normalizationScope,
  });
  const renderedContent = useMemo(() => {
    const content =
      normalizedContent.length > MAX_RENDER_CHARS
        ? `${normalizedContent.slice(0, MAX_RENDER_CHARS)}\n\n...`
        : normalizedContent;
    return linkCitations(content);
  }, [normalizedContent]);
  const headings = useMemo(() => parseHeadings(renderedContent), [renderedContent]);
  const headingIds = useMemo(() => headings.map((heading) => heading.id).join('|'), [headings]);

  const { scrollToBottom, wrapRef } = usePanelContext() || {};
  const lastScrollAtRef = useRef<number>(0);
  const userScrollLockedRef = useRef(false);
  const lastScrollTopRef = useRef(0);
  const copyTimerRef = useRef<number | undefined>(undefined);
  const [activeHeadingId, setActiveHeadingId] = useState('');
  const [readingProgress, setReadingProgress] = useState(0);
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    if (!isStreaming || !normalizedContent) return;
    const now = Date.now();
    if (userScrollLockedRef.current || now - lastScrollAtRef.current < 120) return;
    lastScrollAtRef.current = now;
    scrollToBottom?.();
  }, [normalizedContent, scrollToBottom, isStreaming]);

  useEffect(() => {
    const root = wrapRef?.current;
    if (!root) return;

    const handleScroll = () => {
      const scrollable = Math.max(root.scrollHeight - root.clientHeight, 1);
      const nextProgress = Math.min(1, Math.max(0, root.scrollTop / scrollable));
      setReadingProgress(nextProgress);

      userScrollLockedRef.current = resolveScrollLockState({
        currentScrollTop: root.scrollTop,
        previousScrollTop: lastScrollTopRef.current,
        scrollable,
        locked: userScrollLockedRef.current,
      });
      lastScrollTopRef.current = root.scrollTop;

      let activeId = activeHeadingId;
      for (const heading of headings) {
        const element = document.getElementById(heading.id);
        if (element && element.offsetTop <= root.scrollTop + 96) activeId = heading.id;
      }
      if (activeId !== activeHeadingId) setActiveHeadingId(activeId);
    };

    root.addEventListener('scroll', handleScroll, { passive: true });
    handleScroll();
    return () => root.removeEventListener('scroll', handleScroll);
  }, [activeHeadingId, headings, wrapRef]);

  useEffect(() => {
    const root = wrapRef?.current;
    if (!root || !headingIds || typeof IntersectionObserver === 'undefined') return;

    const observer = new IntersectionObserver(
      (entries) => {
        const visible = entries
          .filter((entry) => entry.isIntersecting)
          .sort((a, b) => a.boundingClientRect.top - b.boundingClientRect.top);
        const first = visible[0]?.target as HTMLElement | undefined;
        if (first?.id) setActiveHeadingId(first.id);
      },
      { root, rootMargin: '0px 0px -70% 0px', threshold: [0, 1] },
    );

    root.querySelectorAll<HTMLElement>('[data-md-heading]').forEach((item) => observer.observe(item));
    return () => observer.disconnect();
  }, [headingIds, renderedContent, wrapRef]);

  useEffect(() => {
    return () => {
      if (copyTimerRef.current) window.clearTimeout(copyTimerRef.current);
    };
  }, []);

  if (!normalizedContent) {
    return <Empty description="暂无内容" className="mx-auto mt-32" />;
  }

  const renderSlugCounts = new Map<string, number>();
  const createHeading = (depth: 1 | 2 | 3) => {
    const HeadingTag = `h${depth}` as 'h1' | 'h2' | 'h3';
    return ({ children }: { children?: ReactNode }) => {
      const id = uniqueSlug(textFromChildren(children), renderSlugCounts);
      return (
        <HeadingTag id={id} data-md-heading className="scroll-mt-24">
          {children}
        </HeadingTag>
      );
    };
  };

  const components = {
    code: CodeBlock,
    h1: createHeading(1),
    h2: createHeading(2),
    h3: createHeading(3),
    a: ({ href, children }: { href?: string; children?: ReactNode }) => {
      const jumpToAnchor = (event: MouseEvent<HTMLAnchorElement>) => {
        if (!href?.startsWith('#')) return;
        const target = document.getElementById(decodeURIComponent(href.slice(1)));
        if (!target) return;
        event.preventDefault();
        target.scrollIntoView({ behavior: 'smooth', block: 'start' });
      };

      return (
        <a
          href={href}
          onClick={jumpToAnchor}
          target={href?.startsWith('#') ? undefined : '_blank'}
          rel={href?.startsWith('#') ? undefined : 'noreferrer'}
        >
          {children}
        </a>
      );
    },
    li: ({ children }: { children?: ReactNode }) => {
      const sourceMatch = /^\[?S(\d+)\]?/i.exec(textFromChildren(children).trim());
      return <li id={sourceMatch ? `source-s${sourceMatch[1]}` : undefined}>{children}</li>;
    },
  };

  const copyMarkdown = () => {
    void navigator.clipboard?.writeText(normalizedContent);
    setCopied(true);
    if (copyTimerRef.current) window.clearTimeout(copyTimerRef.current);
    copyTimerRef.current = window.setTimeout(() => setCopied(false), 1200);
  };

  const downloadMarkdown = () => {
    const url = URL.createObjectURL(new Blob([normalizedContent], { type: 'text/markdown' }));
    const link = document.createElement('a');
    link.href = url;
    link.download = 'deep-research-report.md';
    link.click();
    URL.revokeObjectURL(url);
  };

  return (
    <div className={classNames('w-full markdown-body', className)}>
      <div className="sticky top-0 z-10 mb-3 flex items-center gap-3 border-b border-border bg-background/95 py-2 backdrop-blur">
        <div className="h-1 flex-1 overflow-hidden rounded bg-muted">
          <div
            className="h-full bg-brand transition-[width] duration-150"
            style={{ width: `${Math.round(readingProgress * 100)}%` }}
          />
        </div>
        <button
          type="button"
          aria-label="复制 Markdown"
          title={copied ? '已复制' : '复制 Markdown'}
          onClick={copyMarkdown}
          className="inline-flex size-8 items-center justify-center rounded-md text-muted-foreground hover:bg-muted hover:text-foreground"
        >
          <CopyIcon className="size-4" />
        </button>
        <button
          type="button"
          aria-label="下载 Markdown"
          title="下载 Markdown"
          onClick={downloadMarkdown}
          className="inline-flex size-8 items-center justify-center rounded-md text-muted-foreground hover:bg-muted hover:text-foreground"
        >
          <DownloadIcon className="size-4" />
        </button>
      </div>
      <div
        className={classNames(
          'grid gap-6',
          headings.length > 0 && 'lg:grid-cols-[minmax(0,1fr)_220px]',
        )}
      >
        <article className="min-w-0">
          <ReactMarkdown remarkPlugins={[gfm]} components={components}>
            {renderedContent}
          </ReactMarkdown>
        </article>
        {headings.length ? (
          <nav aria-label="文档目录" className="hidden min-w-0 lg:block">
            <ol className="sticky top-12 max-h-[calc(100vh-8rem)] space-y-1 overflow-auto border-l border-border pl-3 text-[12px] leading-5">
              {headings.map((heading) => (
                <li key={heading.id} className={heading.depth === 3 ? 'pl-4' : heading.depth === 2 ? 'pl-2' : ''}>
                  <a
                    href={`#${heading.id}`}
                    className={classNames(
                      'block truncate text-muted-foreground hover:text-foreground',
                      activeHeadingId === heading.id && 'font-medium text-foreground',
                    )}
                  >
                    {heading.text}
                  </a>
                </li>
              ))}
            </ol>
          </nav>
        ) : null}
      </div>
    </div>
  );
};

export default memo(
  MarkdownRenderer,
  (prevProps, nextProps) =>
    prevProps.markDownContent === nextProps.markDownContent &&
    prevProps.isStreaming === nextProps.isStreaming &&
    prevProps.normalizationScope === nextProps.normalizationScope &&
    prevProps.className === nextProps.className,
);
