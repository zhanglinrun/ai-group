import { describe, expect, it } from 'vitest';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { getPrimaryTaskFile, getPrimaryTaskFileName } from '@/utils/taskArtifacts';
import { useMsgTypes } from './useMsgTypes';
import { resolvePanelView } from './panelResolver';

describe('ActionPanel file content rendering', () => {
  const buildFileTask = (overrides?: Partial<MESSAGE.Task>): MESSAGE.Task => ({
    taskId: 'task-file-001',
    messageTime: '1710000000000',
    messageType: 'file',
    requestId: 'req-file-001',
    messageId: 'msg-file-001',
    finish: true,
    isFinal: true,
    id: 'file-task-001',
    resultMap: {
      command: '读取文件',
      primaryFileName: '风险日报.md',
      previewUrl: 'https://example.com/risk.md',
      downloadUrl: 'https://example.com/risk-download.md',
      fileInfo: [
        {
          fileName: '风险日报.md',
          ossUrl: 'https://example.com/risk.md',
          domainUrl: 'https://example.com/risk-preview.md',
          fileSize: 128,
        },
      ],
    },
    ...overrides,
  });

  it('should resolve file get task from preview url', () => {
    const task = buildFileTask();
    const primaryFile = getPrimaryTaskFile(task as unknown as any);

    expect(primaryFile?.name).toBe('风险日报.md');
    expect(primaryFile?.url).toBe('https://example.com/risk-preview.md');
    expect(primaryFile?.downloadUrl).toBe('https://example.com/risk-download.md');
  });

  it('should synthesize file when replay payload only keeps preview url and file name', () => {
    const task = buildFileTask({
      resultMap: {
        command: '读取文件',
        primaryFileName: '日报汇总.md',
        previewUrl: 'https://example.com/preview-only.md',
        downloadUrl: 'https://example.com/download-only.md',
      },
    });
    const primaryFile = getPrimaryTaskFile(task as unknown as any);

    expect(primaryFile?.name).toBe('日报汇总.md');
    expect(primaryFile?.url).toBe('https://example.com/preview-only.md');
    expect(primaryFile?.downloadUrl).toBe('https://example.com/download-only.md');
    expect(getPrimaryTaskFileName(task as unknown as any)).toBe('日报汇总.md');
  });

  it('should not replace a real fileInfo download with a preview-only result patch', () => {
    const task = buildFileTask({
      resultMap: {
        command: '读取文件',
        primaryFileName: '风险日报.md',
        previewUrl: 'https://example.com/current-preview.md',
        fileInfo: [
          {
            fileName: '风险日报.md',
            ossUrl: 'https://example.com/real-download.md',
            domainUrl: 'https://example.com/real-preview.md',
            fileSize: 128,
          },
        ],
      },
    });

    const primaryFile = getPrimaryTaskFile(task as unknown as any);

    expect(primaryFile?.url).toBe('https://example.com/real-preview.md');
    expect(primaryFile?.downloadUrl).toBe('https://example.com/real-download.md');
  });

  it('should classify file get task as file renderer', () => {
    const task = buildFileTask();
    let msgTypes: ReturnType<typeof useMsgTypes> | undefined;

    const HookProbe = () => {
      msgTypes = useMsgTypes(task as unknown as any);
      return null;
    };

    renderToStaticMarkup(createElement(HookProbe));

    expect(msgTypes?.useFile).toBe(true);
  });

  it('should resolve file get task to file panel view', () => {
    const task = buildFileTask();
    let msgTypes: ReturnType<typeof useMsgTypes> | undefined;

    const HookProbe = () => {
      msgTypes = useMsgTypes(task as unknown as any);
      return null;
    };

    renderToStaticMarkup(createElement(HookProbe));

    const panelView = resolvePanelView({
      taskItem: task as unknown as any,
      msgTypes,
      markDownContent: '',
      primaryFile: getPrimaryTaskFile(task as unknown as any),
    });

    expect(panelView.type).toBe('file');
    if (panelView.type === 'file') {
      expect(panelView.fileName).toBe('风险日报.md');
    }
  });

  it('should route pptx artifacts to an HTML preview while keeping the download', () => {
    const task = buildFileTask({
      messageType: 'ppt',
      resultMap: {
        primaryFileName: '项目演示.pptx',
        previewUrl: 'https://example.com/preview.pptx',
        downloadUrl: 'https://example.com/download.pptx',
      },
    });
    let msgTypes: ReturnType<typeof useMsgTypes> | undefined;
    const HookProbe = () => {
      msgTypes = useMsgTypes(task as unknown as any);
      return null;
    };
    renderToStaticMarkup(createElement(HookProbe));

    const panelView = resolvePanelView({
      taskItem: task as unknown as any,
      msgTypes,
      markDownContent: '',
      primaryFile: getPrimaryTaskFile(task as unknown as any),
    });

    expect(panelView.type).toBe('html');
    if (panelView.type === 'html') {
      expect(panelView.htmlUrl).toBe('https://example.com/preview.pptx');
      expect(panelView.downloadUrl).toBe('https://example.com/download.pptx');
      expect(panelView.label).toBe('PPTX');
    }
  });

  it('should derive the pptx download route from a preview-only tool artifact', () => {
    const task = buildFileTask({
      messageType: 'ppt',
      resultMap: {
        primaryFileName: '项目演示.pptx',
        previewUrl: 'http://127.0.0.1:5173/tool/v1/file_tool/preview/session-1/demo.pptx',
      },
    });
    const panelView = resolvePanelView({
      taskItem: task as unknown as any,
      msgTypes: { usePpt: true },
      markDownContent: '',
      primaryFile: getPrimaryTaskFile(task as unknown as any),
    });

    expect(getPrimaryTaskFile(task as unknown as any)?.downloadUrl).toBe(
      'http://127.0.0.1:5173/tool/v1/file_tool/download/session-1/demo.pptx',
    );
    expect(panelView.type).toBe('html');
    if (panelView.type === 'html') {
      expect(panelView.htmlUrl).toBe(
        'http://127.0.0.1:5173/tool/v1/file_tool/preview/session-1/demo.pptx',
      );
      expect(panelView.downloadUrl).toBe(
        'http://127.0.0.1:5173/tool/v1/file_tool/download/session-1/demo.pptx',
      );
    }
  });
});
