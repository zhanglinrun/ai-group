import type { AxiosAdapter } from 'axios';
import { afterEach, describe, expect, it } from 'vitest';
import request from './request';

const originalAdapter = request.defaults.adapter;

afterEach(() => {
  request.defaults.adapter = originalAdapter;
});

const inspectRequest: AxiosAdapter = async (config) => ({
  data: {
    code: 200,
    data: {
      body: config.data,
      contentType: config.headers.getContentType(),
    },
  },
  status: 200,
  statusText: 'OK',
  headers: {},
  config,
});

describe('request content type', () => {
  it('keeps FormData intact instead of serializing it as JSON', async () => {
    request.defaults.adapter = inspectRequest;
    const form = new FormData();
    form.append('file', new Blob(['skill']), 'SKILL.md');

    const result = (await request.post('/upload', form)) as unknown as {
      body: FormData;
      contentType?: string;
    };

    expect(result.body).toBe(form);
    expect(result.contentType).not.toContain('application/json');
  });

  it('still serializes ordinary objects as JSON', async () => {
    request.defaults.adapter = inspectRequest;

    const result = (await request.post('/json', { enabled: true })) as unknown as {
      body: string;
      contentType?: string;
    };

    expect(result.body).toBe('{"enabled":true}');
    expect(result.contentType).toContain('application/json');
  });
});
