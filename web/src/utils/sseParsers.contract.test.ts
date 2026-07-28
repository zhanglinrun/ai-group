import { describe, expect, it } from 'vitest';

import fixtures from '../../../contracts/agent-stream-events.json';
import { agentStreamEventSchema, parseAgentStreamMessage } from './sseParsers';

const eventTypes = [
  'agent_start',
  'thinking',
  'text',
  'tool_start',
  'tool_end',
  'todo_progress',
  'paused',
  'resume_start',
  'stage_output',
  'error',
  'complete',
] as const;

describe('sealed Agent SSE contract', () => {
  it('validates the 11 shared Java/TypeScript fixtures field by field', () => {
    expect(fixtures).toHaveLength(11);
    expect(fixtures.map((fixture) => agentStreamEventSchema.parse(fixture).type)).toEqual(eventTypes);
  });

  it('requires the SSE event name to match data.type', () => {
    for (const fixture of fixtures) {
      expect(parseAgentStreamMessage(fixture, fixture.type)).toEqual(fixture);
    }
    expect(() => parseAgentStreamMessage(fixtures[0], 'complete')).toThrow(/does not match/);
  });
});
