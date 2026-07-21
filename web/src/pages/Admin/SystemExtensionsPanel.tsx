import { useCallback, useEffect, useRef, useState } from 'react';
import { Modal, Switch, message } from 'antd';
import { FileArchive, Plus, Trash2, Upload } from 'lucide-react';

import {
  adminApi,
  type AdminSystemMcp,
  type AdminSystemSkill,
} from '@/services/admin';

type Tab = 'skills' | 'mcps';

const emptyMcp = (): AdminSystemMcp => ({
  mcpId: '',
  mcpName: '',
  transportType: 'streamable_http',
  transportConfig:
    '{"baseUri":"https://example.com","endpoint":"/mcp","openConnectionOnStartup":false}',
  requestTimeout: 30,
  status: 1,
});

export default function SystemExtensionsPanel() {
  const [tab, setTab] = useState<Tab>('skills');
  const [skills, setSkills] = useState<AdminSystemSkill[]>([]);
  const [mcps, setMcps] = useState<AdminSystemMcp[]>([]);
  const [selectedSkill, setSelectedSkill] = useState<AdminSystemSkill>();
  const [mcpDraft, setMcpDraft] = useState<AdminSystemMcp>(emptyMcp);
  const [mcpEditorOpen, setMcpEditorOpen] = useState(false);
  const fileRef = useRef<HTMLInputElement>(null);

  const refresh = useCallback(async () => {
    try {
      const [nextSkills, nextMcps] = await Promise.all([
        adminApi.listSystemSkills(),
        adminApi.listSystemMcps(),
      ]);
      setSkills(nextSkills || []);
      setMcps(nextMcps || []);
    } catch (error) {
      console.error('加载系统扩展失败', error);
      message.error('加载系统扩展失败');
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const uploadSkill = async (file?: File) => {
    if (!file) return;
    try {
      await adminApi.uploadSystemSkill(file);
      message.success('系统 Skill 已上传');
      await refresh();
    } catch (error) {
      console.error('上传系统 Skill 失败', error);
      message.error('上传失败，请检查 zip 内容');
    } finally {
      if (fileRef.current) fileRef.current.value = '';
    }
  };

  const saveMcp = async () => {
    try {
      JSON.parse(mcpDraft.transportConfig);
      if (mcpDraft.id) {
        await adminApi.updateSystemMcp(mcpDraft);
      } else {
        await adminApi.createSystemMcp(mcpDraft);
      }
      setMcpEditorOpen(false);
      setMcpDraft(emptyMcp());
      message.success('系统 MCP 已保存');
      await refresh();
    } catch (error) {
      console.error('保存系统 MCP 失败', error);
      message.error('MCP 配置必须是有效 JSON');
    }
  };

  return (
    <section className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 className="text-base font-semibold">系统扩展</h2>
          <p className="mt-1 text-xs text-[var(--chat-text-soft)]">
            平台内置能力，对所有 Agent 生效；不会展示在用户端“我的扩展”。
          </p>
        </div>
        {tab === 'skills' ? (
          <>
            <input
              ref={fileRef}
              type="file"
              accept=".zip,application/zip"
              className="hidden"
              onChange={(event) => void uploadSkill(event.target.files?.[0])}
            />
            <button
              type="button"
              onClick={() => fileRef.current?.click()}
              className="inline-flex items-center gap-1.5 rounded-lg bg-[var(--chat-text)] px-3 py-2 text-xs text-white"
            >
              <Upload className="h-3.5 w-3.5" />
              上传系统 Skill
            </button>
          </>
        ) : (
          <button
            type="button"
            onClick={() => {
              setMcpDraft(emptyMcp());
              setMcpEditorOpen(true);
            }}
            className="inline-flex items-center gap-1.5 rounded-lg bg-[var(--chat-text)] px-3 py-2 text-xs text-white"
          >
            <Plus className="h-3.5 w-3.5" />
            添加系统 MCP
          </button>
        )}
      </div>

      <div className="inline-flex rounded-lg border border-[var(--chat-border)] p-1">
        {(['skills', 'mcps'] as Tab[]).map((item) => (
          <button
            key={item}
            type="button"
            onClick={() => setTab(item)}
            className={
              tab === item
                ? 'rounded-md bg-[var(--chat-text)] px-4 py-1.5 text-xs text-white'
                : 'rounded-md px-4 py-1.5 text-xs text-[var(--chat-text-soft)]'
            }
          >
            {item === 'skills' ? '系统 Skills' : '系统 MCP'}
          </button>
        ))}
      </div>

      <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
        {tab === 'skills'
          ? skills.map((skill) => (
              <article
                key={skill.name}
                className="flex min-h-40 flex-col rounded-xl border border-[var(--chat-border)] bg-[var(--chat-surface)] p-4"
              >
                <div className="flex items-start justify-between gap-3">
                  <button
                    type="button"
                    onClick={() => setSelectedSkill(skill)}
                    className="truncate text-left text-sm font-semibold"
                  >
                    {skill.name}
                  </button>
                  <Switch
                    size="small"
                    checked={skill.enabled}
                    onChange={async (enabled) => {
                      await adminApi.setSystemSkillEnabled(skill.name, enabled);
                      await refresh();
                    }}
                  />
                </div>
                <p className="mt-3 line-clamp-3 flex-1 text-xs leading-5 text-[var(--chat-text-soft)]">
                  {skill.description}
                </p>
                <div className="mt-3 flex justify-between">
                  <button
                    type="button"
                    onClick={() => setSelectedSkill(skill)}
                    className="inline-flex items-center gap-1 text-xs text-blue-600"
                  >
                    <FileArchive className="h-3.5 w-3.5" />
                    查看
                  </button>
                  <button
                    type="button"
                    aria-label={`删除系统 Skill ${skill.name}`}
                    onClick={() =>
                      Modal.confirm({
                        title: `删除系统 Skill ${skill.name}？`,
                        onOk: async () => {
                          await adminApi.deleteSystemSkill(skill.name);
                          await refresh();
                        },
                      })
                    }
                    className="text-red-600"
                  >
                    <Trash2 className="h-4 w-4" />
                  </button>
                </div>
              </article>
            ))
          : mcps.map((mcp) => (
              <article
                key={mcp.mcpId}
                className="flex min-h-40 flex-col rounded-xl border border-[var(--chat-border)] bg-[var(--chat-surface)] p-4"
              >
                <div className="flex items-start justify-between gap-3">
                  <button
                    type="button"
                    onClick={() => {
                      setMcpDraft(mcp);
                      setMcpEditorOpen(true);
                    }}
                    className="truncate text-left text-sm font-semibold"
                  >
                    {mcp.mcpName}
                  </button>
                  <Switch
                    size="small"
                    checked={mcp.status === 1}
                    onChange={async (enabled) => {
                      await adminApi.updateSystemMcp({ ...mcp, status: enabled ? 1 : 0 });
                      await refresh();
                    }}
                  />
                </div>
                <p className="mt-2 text-xs text-[var(--chat-text-soft)]">{mcp.mcpId}</p>
                <p className="mt-2 flex-1 text-xs text-[var(--chat-text-soft)]">
                  {mcp.transportType} · 超时 {mcp.requestTimeout || 30} 分钟
                </p>
                <div className="mt-3 flex justify-end">
                  <button
                    type="button"
                    aria-label={`删除系统 MCP ${mcp.mcpName}`}
                    onClick={() =>
                      Modal.confirm({
                        title: `删除系统 MCP ${mcp.mcpName}？`,
                        onOk: async () => {
                          await adminApi.deleteSystemMcp(mcp.mcpId);
                          await refresh();
                        },
                      })
                    }
                    className="text-red-600"
                  >
                    <Trash2 className="h-4 w-4" />
                  </button>
                </div>
              </article>
            ))}
      </div>

      {(tab === 'skills' ? skills.length === 0 : mcps.length === 0) ? (
        <div className="rounded-xl border border-dashed border-[var(--chat-border)] py-12 text-center text-xs text-[var(--chat-text-soft)]">
          暂无系统 {tab === 'skills' ? 'Skill' : 'MCP'}
        </div>
      ) : null}

      <Modal
        open={Boolean(selectedSkill)}
        title={selectedSkill?.name}
        footer={null}
        onCancel={() => setSelectedSkill(undefined)}
        width={680}
      >
        <pre className="max-h-[60vh] overflow-auto whitespace-pre-wrap rounded-lg bg-black/5 p-4 text-xs">
          {selectedSkill?.content}
        </pre>
      </Modal>

      <Modal
        open={mcpEditorOpen}
        title={mcpDraft.id ? '编辑系统 MCP' : '添加系统 MCP'}
        okText="保存"
        onOk={() => void saveMcp()}
        onCancel={() => setMcpEditorOpen(false)}
      >
        <div className="space-y-3 py-2">
          <input
            value={mcpDraft.mcpId}
            disabled={Boolean(mcpDraft.id)}
            placeholder="MCP ID"
            onChange={(event) =>
              setMcpDraft((prev) => ({ ...prev, mcpId: event.target.value }))
            }
            className="w-full rounded-lg border border-[var(--chat-border)] px-3 py-2 text-sm"
          />
          <input
            value={mcpDraft.mcpName}
            placeholder="MCP 名称"
            onChange={(event) =>
              setMcpDraft((prev) => ({ ...prev, mcpName: event.target.value }))
            }
            className="w-full rounded-lg border border-[var(--chat-border)] px-3 py-2 text-sm"
          />
          <select
            value={mcpDraft.transportType}
            onChange={(event) =>
              setMcpDraft((prev) => ({
                ...prev,
                transportType: event.target.value as AdminSystemMcp['transportType'],
              }))
            }
            className="w-full rounded-lg border border-[var(--chat-border)] px-3 py-2 text-sm"
          >
            <option value="streamable_http">Streamable HTTP</option>
            <option value="sse">SSE</option>
            <option value="stdio">STDIO</option>
          </select>
          <textarea
            value={mcpDraft.transportConfig}
            rows={7}
            placeholder="传输配置 JSON"
            onChange={(event) =>
              setMcpDraft((prev) => ({ ...prev, transportConfig: event.target.value }))
            }
            className="w-full rounded-lg border border-[var(--chat-border)] px-3 py-2 font-mono text-xs"
          />
          <input
            type="number"
            min={1}
            value={mcpDraft.requestTimeout || 30}
            onChange={(event) =>
              setMcpDraft((prev) => ({
                ...prev,
                requestTimeout: Number(event.target.value),
              }))
            }
            className="w-full rounded-lg border border-[var(--chat-border)] px-3 py-2 text-sm"
          />
        </div>
      </Modal>
    </section>
  );
}
