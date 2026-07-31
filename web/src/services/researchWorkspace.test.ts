import { describe, expect, it } from 'vitest';

import {
  appendResearchEvent,
  estimateResearchQuota,
  isFileAnalysisEvent,
  researchEventEvidence,
  researchSseHeaders,
  type ResearchRunSnapshot,
} from './researchWorkspace';

const snapshot = (): ResearchRunSnapshot => ({
  requestId: 'run-1',
  sessionId: 'session-1',
  query: 'research question',
  executionMode: 'DEEP',
  attachments: [],
  events: [],
  cursor: 0,
  status: 'RUNNING',
});

describe('research workspace recovery state', () => {
  it('deduplicates replayed events and advances the durable cursor', () => {
    const event = {
      messageId: 'event-1',
      outputType: 'file_analysis',
      payload: { event: 'FILE_ANALYSIS_COMPLETED', fileName: 'brief.md' },
    };
    const once = appendResearchEvent(snapshot(), event, 7);
    const twice = appendResearchEvent(once, event, 7);

    expect(twice.cursor).toBe(7);
    expect(twice.events).toHaveLength(1);
  });

  it('whitelists file-analysis evidence without returning answer or raw payload', () => {
    const event = {
      outputType: 'file_analysis',
      payload: {
        event: 'FILE_ANALYSIS_COMPLETED',
        fileName: 'diagram.png',
        strategy: 'VLM',
        uncertainty: 'medium',
        degraded: false,
        artifactReference: 'artifact:sha256:abc',
        answer: 'private extracted content',
        base64: 'must-not-be-displayed',
      },
    };

    expect(isFileAnalysisEvent(event)).toBe(true);
    expect(researchEventEvidence(event)).toEqual({
      kind: 'FILE_ANALYSIS_COMPLETED',
      fileName: 'diagram.png',
      strategy: 'VLM',
      uncertainty: 'medium',
      degraded: false,
      artifactReference: 'artifact:sha256:abc',
    });
  });

  it('computes a conservative, non-billing quota preflight', () => {
    expect(estimateResearchQuota('STANDARD', 400, 1)).toEqual({
      microcredits: 370_000,
      label: '370,000 microcredits',
      basis: 'STANDARD 预留模型与附件的保守上限',
    });
    expect(estimateResearchQuota('DEEP', 0, 0).microcredits).toBe(750_000);
  });

  it('sends Last-Event-ID only when a durable cursor exists', () => {
    expect(researchSseHeaders('token', 0)).toEqual({ Authorization: 'Bearer token' });
    expect(researchSseHeaders('token', 12)).toEqual({
      Authorization: 'Bearer token',
      'Last-Event-ID': '12',
    });
    expect(researchSseHeaders(undefined, 12)).toEqual({ 'Last-Event-ID': '12' });
  });
});
