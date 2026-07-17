import { beforeEach, describe, expect, it, vi } from 'vitest';

const fetchMocks = vi.hoisted(() => ({
  fetchEventSource: vi.fn(),
}));

vi.mock('@microsoft/fetch-event-source', () => ({
  EventStreamContentType: 'text/event-stream',
  fetchEventSource: fetchMocks.fetchEventSource,
}));

import querySSE from './querySSE';

type FetchOptions = {
  signal: AbortSignal;
};

describe('querySSE transport cancellation', () => {
  beforeEach(() => {
    fetchMocks.fetchEventSource.mockReset();
    fetchMocks.fetchEventSource.mockImplementation(
      (_url: string, options: FetchOptions) =>
        new Promise<void>((_resolve, reject) => {
          options.signal.addEventListener(
            'abort',
            () => reject(new DOMException('aborted', 'AbortError')),
            { once: true },
          );
        }),
    );
  });

  it('返回的 cancel handle 会主动终止当前 transport', async () => {
    const handleClose = vi.fn();
    const cancel = querySSE({
      body: { requestId: 'req-1' },
      handleMessage: vi.fn(),
      handleError: vi.fn(),
      handleClose,
    });
    const options = fetchMocks.fetchEventSource.mock.calls[0]?.[1] as FetchOptions;

    expect(options.signal.aborted).toBe(false);
    cancel();
    cancel();

    expect(options.signal.aborted).toBe(true);
    await vi.waitFor(() => expect(handleClose).toHaveBeenCalledTimes(1));
  });

  it('会话级 AbortSignal 会清理恢复 transport', async () => {
    const parent = new AbortController();
    const handleClose = vi.fn();
    querySSE({
      body: { requestId: 'req-2' },
      handleMessage: vi.fn(),
      handleError: vi.fn(),
      handleClose,
      signal: parent.signal,
    });
    const options = fetchMocks.fetchEventSource.mock.calls[0]?.[1] as FetchOptions;

    parent.abort();

    expect(options.signal.aborted).toBe(true);
    await vi.waitFor(() => expect(handleClose).toHaveBeenCalledTimes(1));
  });

  it('启动前已经 abort 时不创建网络连接', () => {
    const parent = new AbortController();
    const handleClose = vi.fn();
    parent.abort();

    const cancel = querySSE({
      body: { requestId: 'req-3' },
      handleMessage: vi.fn(),
      handleError: vi.fn(),
      handleClose,
      signal: parent.signal,
    });

    expect(fetchMocks.fetchEventSource).not.toHaveBeenCalled();
    expect(handleClose).toHaveBeenCalledTimes(1);
    expect(cancel).toBeTypeOf('function');
  });
});
