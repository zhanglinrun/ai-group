import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';

import DataDialogue from './DataDialogue';

vi.mock('@/components/DataChat', () => ({
  default: () => <div data-chart="true">chart</div>,
}));

describe('DataDialogue', () => {
  it('loading without think text renders the shared localized placeholder', () => {
    const html = renderToStaticMarkup(
      <DataDialogue
        chat={{
          query: '帮我分析最近7天销量',
          loading: true,
          think: '',
          error: '',
        }}
      />,
    );

    expect(html).toContain('正在思考');
    expect(html).not.toContain('思考中');
  });
});
