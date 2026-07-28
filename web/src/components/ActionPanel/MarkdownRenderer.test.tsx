import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';

import MarkdownRenderer, { resolveScrollLockState } from './MarkdownRenderer';

describe('MarkdownRenderer', () => {
  it('renders table of contents, stable duplicate anchors, and citation jumps', () => {
    const html = renderToStaticMarkup(
      <MarkdownRenderer
        markDownContent={[
          '# 总览',
          '开篇判断 [S1]。',
          '## 重复标题',
          '第一段。',
          '## 重复标题',
          '第二段。',
          '## 证据与来源',
          '- [S1] 来源A - https://example.com/a',
        ].join('\n\n')}
      />,
    );

    expect(html).toContain('aria-label="文档目录"');
    expect(html).toContain('id="总览"');
    expect(html).toContain('id="重复标题"');
    expect(html).toContain('id="重复标题-2"');
    expect(html).toContain('href="#重复标题-2"');
    expect(html).toContain('href="#source-s1"');
    expect(html).toContain('id="source-s1"');
    expect(html).toContain('aria-label="复制 Markdown"');
    expect(html).toContain('aria-label="下载 Markdown"');
  });

  it('throttles rendered long markdown while keeping controls visible', () => {
    const html = renderToStaticMarkup(
      <MarkdownRenderer markDownContent={`# 长报告\n\n${'内容'.repeat(16_000)}\n\n## 截断后的标题`} />,
    );

    expect(html).toContain('id="长报告"');
    expect(html).toContain('...');
    expect(html).not.toContain('截断后的标题');
    expect(html).toContain('aria-label="下载 Markdown"');
  });

  it('locks streaming auto-scroll after the reader scrolls upward and unlocks near bottom', () => {
    const locked = resolveScrollLockState({
      currentScrollTop: 280,
      previousScrollTop: 320,
      scrollable: 900,
      locked: false,
    });

    expect(locked).toBe(true);
    expect(
      resolveScrollLockState({
        currentScrollTop: 884,
        previousScrollTop: 280,
        scrollable: 900,
        locked,
      }),
    ).toBe(false);
  });
});
