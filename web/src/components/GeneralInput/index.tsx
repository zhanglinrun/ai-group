import React, { useCallback, useMemo, useRef, useState } from 'react';
import {
  ArrowUpIcon,
  CheckIcon,
  ChevronDownIcon,
  PlusIcon,
  Globe2Icon,
  SearchIcon,
  ZapIcon,
} from 'lucide-react';

import { AI_CHAT_FLOATING_CLASS } from '@/components/ai-elements/ai-chat-surface';
import {
  PromptInput,
  PromptInputActionAddAttachments,
  PromptInputActionMenu,
  PromptInputActionMenuContent,
  PromptInputActionMenuTrigger,
  type PromptInputAttachmentItem,
  PromptInputAttachments,
  PromptInputBody,
  PromptInputFooter,
  PromptInputSubmit,
  PromptInputTextarea,
  PromptInputTools,
} from '@/components/ai-elements/prompt-input';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '@/components/ui/tooltip';
import ChatRoleSelector from '@/components/ChatRoleSelector';
import ModelSelector from '@/components/ModelSelector';
import type { ModelItem } from '@/services/models';
import { cn } from '@/lib/utils';
import { defaultProduct, productList } from '@/utils/constants';
import UploadAttachmentChip from './UploadAttachmentChip';
import { buildSubmitPayload, resolveInputMode, type InputModeKey } from './inputMode';
import { useAttachmentUploads } from './useAttachmentUploads';

type Props = {
  sessionId: string;
  placeholder: string;
  showBtn: boolean;
  disabled: boolean;
  loading?: boolean;
  onStop?: () => void;
  size: string;
  product?: CHAT.Product;
  executionMode?: CHAT.ExecutionMode;
  displayOutput?: CHAT.Product;
  chatRole?: CHAT.ConversationRole | null;
  chatRoles?: CHAT.FixRole[];
  showRoleSelector?: boolean;
  /** 可选模型列表（管理端配置且启用）。 */
  models?: ModelItem[];
  /** 当前选择的模型 ID。 */
  selectedModelId?: string;
  /** 是否展示模型选择器。 */
  showModelSelector?: boolean;
  send: (p: CHAT.TInputInfo) => void;
  onSelectionChange?: (selection: {
    product: CHAT.Product;
    executionMode: CHAT.ExecutionMode;
  }) => void;
  onRoleSelect?: (role: CHAT.FixRole) => void;
  onSelectModel?: (modelId: string) => void;
};

const OUTPUT_TYPES = ['html', 'docs', 'ppt', 'table'];
const OUTPUT_PRODUCTS = productList.filter(
  (item) => item.type === 'chat' || OUTPUT_TYPES.includes(item.type),
) as CHAT.Product[];
const DEFAULT_STRUCTURED_OUTPUT_PRODUCT =
  (OUTPUT_PRODUCTS.find((item) => OUTPUT_TYPES.includes(item.type)) as CHAT.Product | undefined) ??
  defaultProduct;

const MODE_OPTIONS: Array<{
  key: InputModeKey;
  label: string;
  description: string;
  icon: typeof ZapIcon;
}> = [
  {
    key: 'standard',
    label: '普通问题',
    description: '直接提问与日常任务',
    icon: ZapIcon,
  },
  {
    key: 'deep',
    label: '深度调研',
    description: '并行检索、汇总并生成报告',
    icon: SearchIcon,
  },
];

const VISIBLE_MODE_OPTIONS = MODE_OPTIONS;

const getOutputProduct = (product?: CHAT.Product, displayOutput?: CHAT.Product) => {
  if (product && (product.type === 'chat' || OUTPUT_TYPES.includes(product.type))) {
    return product;
  }
  if (displayOutput && OUTPUT_TYPES.includes(displayOutput.type)) {
    return displayOutput;
  }
  return DEFAULT_STRUCTURED_OUTPUT_PRODUCT;
};

const getProductLabel = (name: string) => name.replace('模式', '');
const getOutputShortDescription = (type: string) => {
  switch (type) {
    case 'html':
      return '网页页面';
    case 'docs':
      return '文档报告';
    case 'ppt':
      return '演示文稿';
    case 'table':
      return '数据表格';
    case 'chat':
      return '普通对话';
    default:
      return '结构化输出';
  }
};

type SelectorTone = {
  icon: string;
  iconActive: string;
  check: string;
};

const BRAND_TONE: SelectorTone = {
  icon: 'text-[var(--chat-text-soft)]',
  iconActive: 'bg-brand-soft text-brand',
  check: 'text-brand',
};

const MODE_TONES: Record<InputModeKey, SelectorTone> = {
  standard: BRAND_TONE,
  deep: BRAND_TONE,
};

const OUTPUT_TONES: Record<string, SelectorTone> = {
  html: BRAND_TONE,
  docs: BRAND_TONE,
  ppt: BRAND_TONE,
  table: BRAND_TONE,
};

const selectorTrayClassName =
  'flex min-w-0 flex-wrap items-center gap-1 rounded-full bg-transparent p-0.5';

const chipButtonClassName = (active: boolean, disabled?: boolean) =>
  cn(
    'group inline-flex h-9 max-w-full items-center gap-2 rounded-full border border-transparent px-3 pr-3 text-[14px] font-medium transition-all duration-200',
    'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background',
    disabled && 'cursor-not-allowed opacity-50',
    !disabled && 'hover:bg-[var(--chat-surface-soft)]',
    active ? 'bg-brand-soft text-brand' : 'bg-transparent text-[var(--chat-text)]',
  );

const chipIconWrapClassName = (tone: SelectorTone, active: boolean) =>
  cn(
    'flex size-[26px] shrink-0 items-center justify-center rounded-full transition-all duration-200',
    active
      ? tone.iconActive
      : cn('bg-transparent ring-0 group-hover:bg-[var(--chat-surface)]', tone.icon),
  );

const menuContentClassName =
  'rounded-[16px] border border-border bg-popover p-0.5 shadow-[0_10px_28px_-18px_rgba(15,23,42,0.2)]';

const menuTitleClassName =
  'px-2 pb-1 pt-0.5 text-[10px] font-semibold tracking-[0.06em] text-muted-foreground uppercase';

const menuItemClassName = (active: boolean) =>
  cn(
    'flex w-full gap-1.5 rounded-xl border border-transparent px-1.5 py-2 text-left transition-all duration-200',
    active ? 'bg-brand-soft' : 'bg-transparent hover:bg-[var(--chat-surface-soft)]',
  );

const menuIconWrapClassName = (tone: SelectorTone, active: boolean) =>
  cn(
    'mt-0.5 flex size-6.5 shrink-0 items-center justify-center rounded-lg transition-all duration-200',
    active ? tone.iconActive : cn('bg-transparent ring-0', tone.icon),
  );

const GeneralInput: ReactorType.FC<Props> = (props) => {
  const {
    sessionId,
    placeholder,
    showBtn,
    disabled,
    loading = false,
    onStop,
    size,
    product,
    executionMode = 'STANDARD',
    displayOutput,
    chatRole,
    chatRoles = [],
    showRoleSelector = false,
    models = [],
    selectedModelId,
    showModelSelector = false,
    send,
    onSelectionChange,
    onRoleSelect,
    onSelectModel,
  } = props;

  const [question, setQuestion] = useState('');
  const [modeMenuOpen, setModeMenuOpen] = useState(false);
  const [outputMenuOpen, setOutputMenuOpen] = useState(false);
  const [onlineEnabled, setOnlineEnabled] = useState(false);
  const tempData = useRef<{ compositing?: boolean }>({});
  const {
    attachmentUploads,
    attachmentOrder,
    clearAttachmentUploads,
    removeAttachmentUpload,
    retryAttachmentUpload,
    addAttachmentUploads,
  } = useAttachmentUploads(sessionId);

  const currentMode = resolveInputMode(executionMode);
  const resolvedOutputProduct = useMemo(
    () => getOutputProduct(product, displayOutput),
    [displayOutput, product],
  );

  const visibleMode = currentMode;
  const visibleOutputProduct = resolvedOutputProduct;
  const currentModeOption =
    MODE_OPTIONS.find((item) => item.key === visibleMode) ?? MODE_OPTIONS[0];
  const CurrentModeIcon = currentModeOption.icon;
  const currentModeTone = MODE_TONES[currentModeOption.key];
  const visibleOutputTone = OUTPUT_TONES[visibleOutputProduct.type] ?? OUTPUT_TONES.html;
  const hasUploadingAttachment = attachmentOrder.some((id) => {
    const status = attachmentUploads[id]?.status;
    return status === 'pending' || status === 'uploading';
  });
  const hasFailedAttachment = attachmentOrder.some(
    (id) => attachmentUploads[id]?.status === 'error',
  );
  const uploadedFiles = attachmentOrder
    .map((id) => attachmentUploads[id]?.uploadedFile)
    .filter((file): file is CHAT.TFile => Boolean(file));
  const canSend =
    Boolean(question.trim()) && !disabled && !hasUploadingAttachment && !hasFailedAttachment;
  const showOutputSelector = showBtn && visibleMode !== 'deep';

  const handleAttachmentsAdded = useCallback(
    (attachments: PromptInputAttachmentItem[]) => {
      const nextAttachments = attachments.filter(
        (attachment): attachment is PromptInputAttachmentItem & { file: File } =>
          Boolean(attachment.file),
      );
      addAttachmentUploads(
        nextAttachments.map((attachment) => ({
          id: attachment.id,
          file: attachment.file,
        })),
      );
    },
    [addAttachmentUploads],
  );

  const handleSelectionChange = (nextProduct: CHAT.Product, nextMode: InputModeKey) => {
    onSelectionChange?.({
      product: nextProduct,
      executionMode: nextMode === 'deep' ? 'DEEP' : 'STANDARD',
    });
  };

  const handleModeSelect = (modeKey: InputModeKey) => {
    handleSelectionChange(visibleOutputProduct, modeKey);
    setModeMenuOpen(false);
  };

  const handleOutputSelect = (nextOutput: CHAT.Product) => {
    handleSelectionChange(nextOutput, visibleMode);
    setOutputMenuOpen(false);
  };

  const handleSubmit = ({ text }: { text: string; files: unknown[] }) => {
    if (!text.trim() || disabled || hasUploadingAttachment || hasFailedAttachment) return;

    send(
      buildSubmitPayload({
        question: text,
        visibleMode,
        visibleOutputProduct,
        uploadedFiles,
        modelId: selectedModelId,
        online: onlineEnabled,
      }),
    );

    setQuestion('');
    clearAttachmentUploads();
  };

  const handleKeyDown: React.KeyboardEventHandler<HTMLTextAreaElement> = (event) => {
    if (event.key !== 'Enter') return;
    if (tempData.current.compositing || event.nativeEvent.isComposing) return;

    if (event.metaKey || event.ctrlKey) {
      event.preventDefault();
      const textarea = event.currentTarget;
      const { selectionStart, selectionEnd } = textarea;
      const nextValue = question.slice(0, selectionStart) + '\n' + question.slice(selectionEnd);
      setQuestion(nextValue);
      requestAnimationFrame(() => {
        textarea.selectionStart = selectionStart + 1;
        textarea.selectionEnd = selectionStart + 1;
        textarea.focus();
      });
      return;
    }

    if (!canSend) {
      event.preventDefault();
      return;
    }

    event.preventDefault();
    event.currentTarget.form?.requestSubmit();
  };

  return (
    <TooltipProvider>
      <div className="w-full">
        <PromptInput
          accept="image/*,application/pdf,.txt,.md,.csv,.xlsx,.docx"
          className={cn(
            'reactor-input-flat w-full rounded-[24px] transition-all duration-300',
            size === 'big' ? 'rounded-[28px]' : 'rounded-[22px]',
          )}
          convertBlobUrlsOnSubmit={false}
          multiple
          onAttachmentsAdded={handleAttachmentsAdded}
          onSubmit={handleSubmit}
        >
          <PromptInputBody>
            <PromptInputAttachments className="px-4 pt-3">
              {(file) => (
                <UploadAttachmentChip
                  key={file.id}
                  attachment={file}
                  uploadState={attachmentUploads[file.id]}
                  onRemoveAttachment={removeAttachmentUpload}
                  onRetryAttachment={retryAttachmentUpload}
                />
              )}
            </PromptInputAttachments>

            <PromptInputTextarea
              className={cn(
                'px-4 text-[14px] leading-6 text-[var(--chat-text)] placeholder:text-[var(--chat-text-soft)] placeholder:opacity-100',
                'focus:placeholder:text-[var(--chat-text-soft)]/50',
                size === 'big' ? 'min-h-24 pt-4 text-[15px]' : 'min-h-16 pt-3.5',
              )}
              disabled={disabled}
              onChange={(event) => setQuestion(event.target.value)}
              onCompositionEnd={() => {
                tempData.current.compositing = false;
              }}
              onCompositionStart={() => {
                tempData.current.compositing = true;
              }}
              onKeyDown={handleKeyDown}
              placeholder={placeholder}
              value={question}
            />
          </PromptInputBody>

          <PromptInputFooter
            className={cn(
              'items-center gap-2 border-t border-[var(--chat-border)]/70 px-3 pb-2.5 pt-2',
              showBtn ? 'flex-wrap sm:flex-nowrap' : 'justify-between',
            )}
          >
            <PromptInputTools
              className={cn(
                'min-w-0 flex-1 flex-wrap items-center gap-1',
                !showBtn && 'w-auto flex-none gap-1.5',
              )}
            >
              <PromptInputActionMenu>
                <PromptInputActionMenuTrigger
                  size="icon-sm"
                  variant="ghost"
                  disabled={disabled}
                  className="rounded-full border-0 bg-transparent text-[var(--chat-text)] shadow-none ring-0 transition-all duration-200 hover:bg-[var(--chat-surface-soft)] focus-visible:ring-0"
                >
                  <PlusIcon className="size-5" />
                </PromptInputActionMenuTrigger>
                <PromptInputActionMenuContent className={cn('min-w-[180px]', menuContentClassName)}>
                  <PromptInputActionAddAttachments label="上传附件" />
                </PromptInputActionMenuContent>
              </PromptInputActionMenu>

              {showBtn ? (
                <Tooltip>
                  <TooltipTrigger asChild>
                    <button
                      type="button"
                      aria-pressed={onlineEnabled}
                      disabled={disabled}
                      onClick={() => setOnlineEnabled((enabled) => !enabled)}
                      className={cn(
                        'inline-flex h-8 items-center gap-1.5 rounded-full px-2.5 text-[12px] font-medium transition-colors',
                        onlineEnabled
                          ? 'bg-[#0071e3]/10 text-[#0071e3]'
                          : 'text-[var(--chat-text-soft)] hover:bg-[var(--chat-surface-soft)]',
                      )}
                    >
                      <Globe2Icon className="h-4 w-4" />
                      联网搜索
                    </button>
                  </TooltipTrigger>
                  <TooltipContent className={AI_CHAT_FLOATING_CLASS} side="top">
                    {onlineEnabled ? '关闭联网搜索' : '开启联网搜索'}
                  </TooltipContent>
                </Tooltip>
              ) : null}

              {showBtn ? (
                <span className="mx-0.5 hidden h-5 w-px bg-[var(--chat-border)]/70 sm:block" />
              ) : null}

              {showBtn ? (
                <div className={cn(selectorTrayClassName, 'flex-1 sm:flex-none')}>
                  {showRoleSelector ? (
                    <ChatRoleSelector
                      roles={chatRoles}
                      selectedRole={chatRole}
                      disabled={disabled}
                      onSelect={(role) => onRoleSelect?.(role)}
                    />
                  ) : null}
                  {showModelSelector ? (
                    <ModelSelector
                      models={models}
                      selectedModelId={selectedModelId}
                      disabled={disabled}
                      onSelect={(modelId) => onSelectModel?.(modelId)}
                    />
                  ) : null}
                  <DropdownMenu open={modeMenuOpen} onOpenChange={setModeMenuOpen}>
                    <DropdownMenuTrigger asChild>
                      <button
                        type="button"
                        aria-pressed={true}
                        disabled={disabled}
                        className={chipButtonClassName(true, disabled)}
                      >
                        <span className={chipIconWrapClassName(currentModeTone, true)}>
                          <CurrentModeIcon className="size-4" />
                        </span>
                        <span className="truncate">{currentModeOption.label}</span>
                        <ChevronDownIcon
                          className={cn(
                            'size-4 shrink-0 text-[var(--chat-text-muted)] transition-transform',
                            modeMenuOpen && 'rotate-180',
                          )}
                        />
                      </button>
                    </DropdownMenuTrigger>
                    <DropdownMenuContent
                      align="start"
                      side="bottom"
                      sideOffset={12}
                      className={cn('w-[190px]', menuContentClassName)}
                    >
                      <div className={menuTitleClassName}>推理模式</div>
                      <div className="space-y-1">
                        {VISIBLE_MODE_OPTIONS.map((option) => {
                          const isActive = option.key === visibleMode;
                          const tone = MODE_TONES[option.key];
                          return (
                            <button
                              key={option.key}
                              type="button"
                              className={menuItemClassName(isActive)}
                              onClick={() => handleModeSelect(option.key)}
                            >
                              <span className={menuIconWrapClassName(tone, isActive)}>
                                <option.icon className="size-3.5" />
                              </span>
                              <span className="min-w-0 flex-1 pr-0.5">
                                <span className="block text-[14px] font-medium tracking-[-0.01em] text-[var(--chat-text)]">
                                  {option.label}
                                </span>
                                <span className="mt-0.5 block text-[11px] leading-4 text-[var(--chat-text-soft)]">
                                  {option.description}
                                </span>
                              </span>
                              {isActive ? (
                                <CheckIcon className={cn('mt-1 size-3 shrink-0', tone.check)} />
                              ) : null}
                            </button>
                          );
                        })}
                      </div>
                    </DropdownMenuContent>
                  </DropdownMenu>

                  {showOutputSelector ? (
                    <DropdownMenu open={outputMenuOpen} onOpenChange={setOutputMenuOpen}>
                      <DropdownMenuTrigger asChild>
                        <button
                          type="button"
                          aria-pressed={true}
                          disabled={disabled}
                          className={chipButtonClassName(true, disabled)}
                        >
                          <span className={chipIconWrapClassName(visibleOutputTone, true)}>
                            <i
                              className={cn('font_family text-[14px]', visibleOutputProduct.img)}
                            />
                          </span>
                          <span className="truncate">
                            输出 · {getProductLabel(visibleOutputProduct.name)}
                          </span>
                          <ChevronDownIcon
                            className={cn(
                              'size-4 shrink-0 text-[var(--chat-text-muted)] transition-transform',
                              outputMenuOpen && 'rotate-180',
                            )}
                          />
                        </button>
                      </DropdownMenuTrigger>
                      <DropdownMenuContent
                        align="start"
                        side="bottom"
                        avoidCollisions={false}
                        sideOffset={12}
                        className={cn('w-[186px]', menuContentClassName)}
                      >
                        <div className={menuTitleClassName}>输出格式</div>
                        <div className="space-y-1">
                          {OUTPUT_PRODUCTS.map((item) => {
                            const isActive = item.type === visibleOutputProduct.type;
                            const tone = OUTPUT_TONES[item.type] ?? visibleOutputTone;
                            return (
                              <button
                                key={item.type}
                                type="button"
                                className={menuItemClassName(isActive)}
                                onClick={() => handleOutputSelect(item)}
                              >
                                <span className={menuIconWrapClassName(tone, isActive)}>
                                  <i className={cn('font_family text-[13px]', item.img)} />
                                </span>
                                <span className="min-w-0 flex-1 pr-0.5">
                                  <span className="block text-[14px] font-medium tracking-[-0.01em] text-[var(--chat-text)]">
                                    {getProductLabel(item.name)}
                                  </span>
                                  <span className="mt-0.5 block text-[11px] leading-4 text-[var(--chat-text-soft)]">
                                    {getOutputShortDescription(item.type)}
                                  </span>
                                </span>
                                {isActive ? (
                                  <CheckIcon className={cn('size-3 shrink-0', tone.check)} />
                                ) : null}
                              </button>
                            );
                          })}
                        </div>
                      </DropdownMenuContent>
                    </DropdownMenu>
                  ) : null}
                </div>
              ) : showRoleSelector ? (
                <ChatRoleSelector
                  roles={chatRoles}
                  selectedRole={chatRole}
                  disabled={disabled}
                  onSelect={(role) => onRoleSelect?.(role)}
                />
              ) : null}
            </PromptInputTools>

            <PromptInputTools className="ml-auto shrink-0 gap-1">
              <Tooltip>
                <TooltipTrigger asChild>
                  {loading && onStop ? (
                    <button
                      type="button"
                      aria-label="停止任务"
                      onClick={onStop}
                      className="flex size-9 items-center justify-center rounded-full bg-rose-500 text-white shadow-md transition-all duration-200 hover:scale-105 hover:bg-rose-600 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-rose-400 focus-visible:ring-offset-2"
                    >
                      <span className="size-3 rounded-[2px] bg-white" />
                    </button>
                  ) : (
                    <PromptInputSubmit
                      className="size-9 rounded-full bg-[var(--chat-text)] text-[var(--chat-surface)] shadow-md transition-all duration-300 hover:scale-105 hover:shadow-lg hover:shadow-[var(--primary)]/15 disabled:bg-[var(--chat-surface-muted)] disabled:text-[var(--chat-text-muted)] disabled:shadow-none disabled:scale-100"
                      disabled={!canSend}
                      variant="default"
                    >
                      <ArrowUpIcon className="size-5" />
                    </PromptInputSubmit>
                  )}
                </TooltipTrigger>
                <TooltipContent className={AI_CHAT_FLOATING_CLASS} side="top">
                  {loading && onStop ? '停止任务' : '发送'}
                </TooltipContent>
              </Tooltip>
            </PromptInputTools>
          </PromptInputFooter>
        </PromptInput>
      </div>
    </TooltipProvider>
  );
};

export default GeneralInput;
