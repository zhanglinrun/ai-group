import { useCallback, useEffect, useRef, useState } from 'react';
import { FileArchive, PlugZap, Plus, RefreshCw, Trash2, Upload, Zap } from 'lucide-react';
import { Modal, Switch, message } from 'antd';

import {
  userMcpApi,
  userSkillApi,
  type UserMcp,
  type UserSkill,
} from '@/services/userExtensions';

type ExtensionTab = 'skills' | 'mcps';

const emptyMcp = (): UserMcp => ({
  name: '',
  serverUrl: '',
  transportType: 'streamable_http',
  enabled: true,
});

export default function UserExtensions() {
  const [tab, setTab] = useState<ExtensionTab>('skills');
  const [skills, setSkills] = useState<UserSkill[]>([]);
  const [mcps, setMcps] = useState<UserMcp[]>([]);
  const [loading, setLoading] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [selectedSkill, setSelectedSkill] = useState<UserSkill>();
  const [mcpEditorOpen, setMcpEditorOpen] = useState(false);
  const [mcpDraft, setMcpDraft] = useState<UserMcp>(emptyMcp);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      const [nextSkills, nextMcps] = await Promise.all([userSkillApi.list(), userMcpApi.list()]);
      setSkills(nextSkills || []);
      setMcps(nextMcps || []);
    } catch (error) {
      console.error('加载用户扩展失败', error);
      message.error('加载扩展失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const uploadSkill = async (file?: File) => {
    if (!file) return;
    setUploading(true);
    try {
      await userSkillApi.upload(file);
      message.success('Skill 已上传');
      await refresh();
    } catch (error) {
      console.error('上传 Skill 失败', error);
      message.error('上传失败，请检查 zip 内容');
    } finally {
      setUploading(false);
      if (fileInputRef.current) fileInputRef.current.value = '';
    }
  };

  const saveMcp = async () => {
    try {
      await userMcpApi.save(mcpDraft);
      message.success('MCP 已保存');
      setMcpEditorOpen(false);
      setMcpDraft(emptyMcp());
      await refresh();
    } catch (error) {
      console.error('保存 MCP 失败', error);
      message.error('连接或地址校验失败');
    }
  };

  return (
    <main className="mx-auto min-h-full w-full max-w-[1120px] px-5 py-7 sm:px-8">
      <div className="mb-6 flex flex-wrap items-center justify-between gap-4">
        <div>
          <h1 className="flex items-center gap-2 text-[20px] font-semibold text-[var(--chat-text)]">
            <PlugZap className="h-5 w-5 text-[#0071e3]" />
            我的扩展
          </h1>
          <p className="mt-1 text-[13px] text-[var(--chat-text-muted)]">我的 Skills 与 MCP</p>
        </div>
        <div className="flex items-center gap-2">
          <button
            type="button"
            title="刷新"
            aria-label="刷新扩展"
            onClick={() => void refresh()}
            className="flex size-9 items-center justify-center rounded-lg border border-[var(--chat-border)] text-[var(--chat-text-soft)] hover:bg-[var(--chat-surface-soft)]"
          >
            <RefreshCw className={`h-4 w-4 ${loading ? 'animate-spin' : ''}`} />
          </button>
          {tab === 'skills' ? (
            <>
              <input
                ref={fileInputRef}
                type="file"
                accept=".zip,application/zip"
                className="hidden"
                onChange={(event) => void uploadSkill(event.target.files?.[0])}
              />
              <button
                type="button"
                disabled={uploading}
                onClick={() => fileInputRef.current?.click()}
                className="inline-flex h-9 items-center gap-2 rounded-lg bg-[#0071e3] px-3.5 text-[13px] font-medium text-white disabled:opacity-50"
              >
                <Upload className="h-4 w-4" />
                上传 Skill
              </button>
            </>
          ) : (
            <button
              type="button"
              onClick={() => {
                setMcpDraft(emptyMcp());
                setMcpEditorOpen(true);
              }}
              className="inline-flex h-9 items-center gap-2 rounded-lg bg-[#0071e3] px-3.5 text-[13px] font-medium text-white"
            >
              <Plus className="h-4 w-4" />
              添加 MCP
            </button>
          )}
        </div>
      </div>

      <div className="mb-6 inline-flex rounded-lg bg-[var(--chat-surface-muted)] p-1">
        {(['skills', 'mcps'] as ExtensionTab[]).map((item) => (
          <button
            key={item}
            type="button"
            onClick={() => setTab(item)}
            className={`h-8 rounded-md px-4 text-[13px] font-medium ${
              tab === item
                ? 'bg-[var(--chat-surface)] text-[var(--chat-text)] shadow-sm'
                : 'text-[var(--chat-text-soft)]'
            }`}
          >
            {item === 'skills' ? 'Skills' : 'MCP'}
          </button>
        ))}
      </div>

      {tab === 'skills' ? (
        <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
          {skills.map((skill) => (
            <article
              key={skill.name}
              className="flex min-h-[168px] flex-col rounded-lg border border-[var(--chat-border)] bg-[var(--chat-surface)] p-4"
            >
              <div className="flex items-start justify-between gap-3">
                <button
                  type="button"
                  onClick={() => setSelectedSkill(skill)}
                  className="min-w-0 text-left"
                >
                  <span className="flex items-center gap-2 font-semibold text-[var(--chat-text)]">
                    <Zap className="h-4 w-4 text-[#0071e3]" />
                    <span className="truncate">{skill.name}</span>
                  </span>
                </button>
                <Switch
                  size="small"
                  checked={skill.enabled}
                  onChange={async (enabled) => {
                    await userSkillApi.setEnabled(skill.name, enabled);
                    await refresh();
                  }}
                />
              </div>
              <p className="mt-3 line-clamp-3 flex-1 text-[13px] leading-5 text-[var(--chat-text-soft)]">
                {skill.description}
              </p>
              <div className="mt-3 flex items-center justify-between">
                <button
                  type="button"
                  onClick={() => setSelectedSkill(skill)}
                  className="inline-flex items-center gap-1.5 text-[12px] text-[#0071e3]"
                >
                  <FileArchive className="h-3.5 w-3.5" />
                  查看
                </button>
                <button
                  type="button"
                  title="删除 Skill"
                  aria-label={`删除 Skill ${skill.name}`}
                  onClick={() => {
                    Modal.confirm({
                      title: `删除 ${skill.name}？`,
                      onOk: async () => {
                        await userSkillApi.delete(skill.name);
                        await refresh();
                      },
                    });
                  }}
                  className="flex size-8 items-center justify-center rounded-md text-[var(--chat-text-muted)] hover:bg-red-50 hover:text-red-600"
                >
                  <Trash2 className="h-4 w-4" />
                </button>
              </div>
            </article>
          ))}
        </div>
      ) : (
        <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
          {mcps.map((mcp) => (
            <article
              key={mcp.id}
              className="flex min-h-[168px] flex-col rounded-lg border border-[var(--chat-border)] bg-[var(--chat-surface)] p-4"
            >
              <div className="flex items-start justify-between gap-3">
                <button
                  type="button"
                  onClick={() => {
                    setMcpDraft(mcp);
                    setMcpEditorOpen(true);
                  }}
                  className="min-w-0 text-left"
                >
                  <span className="flex items-center gap-2 font-semibold text-[var(--chat-text)]">
                    <PlugZap className="h-4 w-4 text-emerald-600" />
                    <span className="truncate">{mcp.name}</span>
                  </span>
                </button>
                <Switch
                  size="small"
                  checked={mcp.enabled}
                  onChange={async (enabled) => {
                    if (!mcp.id) return;
                    await userMcpApi.setEnabled(mcp.id, enabled);
                    await refresh();
                  }}
                />
              </div>
              <p className="mt-3 break-all text-[12px] leading-5 text-[var(--chat-text-soft)]">
                {mcp.serverUrl}
              </p>
              <p className="mt-2 flex-1 text-[12px] text-[var(--chat-text-muted)]">
                {mcp.transportType === 'sse' ? 'SSE' : 'Streamable HTTP'} · {mcp.toolCount || 0}{' '}
                个工具
              </p>
              <div className="mt-3 flex justify-end">
                <button
                  type="button"
                  title="删除 MCP"
                  aria-label={`删除 MCP ${mcp.name}`}
                  onClick={() => {
                    if (!mcp.id) return;
                    Modal.confirm({
                      title: `删除 ${mcp.name}？`,
                      onOk: async () => {
                        await userMcpApi.delete(mcp.id!);
                        await refresh();
                      },
                    });
                  }}
                  className="flex size-8 items-center justify-center rounded-md text-[var(--chat-text-muted)] hover:bg-red-50 hover:text-red-600"
                >
                  <Trash2 className="h-4 w-4" />
                </button>
              </div>
            </article>
          ))}
        </div>
      )}

      {!loading && (tab === 'skills' ? skills.length === 0 : mcps.length === 0) ? (
        <div className="py-24 text-center text-[13px] text-[var(--chat-text-muted)]">
          暂无{tab === 'skills' ? ' Skill' : ' MCP'}
        </div>
      ) : null}

      <Modal
        open={Boolean(selectedSkill)}
        title={selectedSkill?.name}
        footer={null}
        onCancel={() => setSelectedSkill(undefined)}
        width={680}
      >
        <pre className="max-h-[60vh] overflow-auto whitespace-pre-wrap rounded-lg bg-[var(--chat-surface-muted)] p-4 text-[12px] leading-5">
          {selectedSkill?.content}
        </pre>
      </Modal>

      <Modal
        open={mcpEditorOpen}
        title={mcpDraft.id ? '编辑 MCP' : '添加 MCP'}
        okText="保存"
        cancelText="取消"
        onOk={() => void saveMcp()}
        onCancel={() => setMcpEditorOpen(false)}
      >
        <div className="space-y-4 py-2">
          <label className="block text-[13px] text-[var(--chat-text-soft)]">
            名称
            <input
              value={mcpDraft.name}
              onChange={(event) => setMcpDraft((prev) => ({ ...prev, name: event.target.value }))}
              className="mt-1.5 h-10 w-full rounded-lg border border-[var(--chat-border)] bg-[var(--chat-surface)] px-3 text-[var(--chat-text)] outline-none"
            />
          </label>
          <label className="block text-[13px] text-[var(--chat-text-soft)]">
            服务地址
            <input
              value={mcpDraft.serverUrl}
              onChange={(event) =>
                setMcpDraft((prev) => ({ ...prev, serverUrl: event.target.value }))
              }
              placeholder="https://example.com/mcp"
              className="mt-1.5 h-10 w-full rounded-lg border border-[var(--chat-border)] bg-[var(--chat-surface)] px-3 text-[var(--chat-text)] outline-none"
            />
          </label>
          <label className="block text-[13px] text-[var(--chat-text-soft)]">
            协议
            <select
              value={mcpDraft.transportType}
              onChange={(event) =>
                setMcpDraft((prev) => ({
                  ...prev,
                  transportType: event.target.value as UserMcp['transportType'],
                }))
              }
              className="mt-1.5 h-10 w-full rounded-lg border border-[var(--chat-border)] bg-[var(--chat-surface)] px-3 text-[var(--chat-text)] outline-none"
            >
              <option value="streamable_http">Streamable HTTP</option>
              <option value="sse">SSE</option>
            </select>
          </label>
        </div>
      </Modal>
    </main>
  );
}
