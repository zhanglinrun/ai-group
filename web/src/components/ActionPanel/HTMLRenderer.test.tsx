import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import HTMLRenderer from './HTMLRenderer';

describe('HTMLRenderer', () => {
  it('isolates generated HTML from the application origin', () => {
    const html = renderToStaticMarkup(<HTMLRenderer htmlUrl="/artifacts/report.html" />);

    expect(html).toContain('sandbox="allow-scripts"');
    expect(html).not.toContain('allow-same-origin');
  });
});
