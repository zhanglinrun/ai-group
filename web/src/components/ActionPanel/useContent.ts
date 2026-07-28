import { buildDeepSearchExtendMarkdown, resolveDeepSearchStage } from '@/utils/deepSearch';
import { PanelItemType } from './type';

function buildToolCallMarkdown(resultMap?: PanelItemType['resultMap']) {
  if (!resultMap) {
    return '';
  }

  const contentBlocks: string[] = [];
  const summary = typeof resultMap.summary === 'string' ? resultMap.summary.trim() : '';
  if (summary) {
    contentBlocks.push(summary);
  } else if (resultMap.toolName) {
    contentBlocks.push(`正在调用 \`${resultMap.toolName}\``);
  }

  const input = resultMap.input || resultMap.toolParam;
  if (input && typeof input === 'object') {
    contentBlocks.push(`\`\`\`json\n${JSON.stringify(input, null, 2)}\n\`\`\``);
  }

  return contentBlocks.join('\n\n');
}

function buildDeepResearchMarkdown(resultMap?: PanelItemType['resultMap']) {
  if (!resultMap) {
    return '';
  }
  if (resultMap.previewMarkdown) {
    return resultMap.previewMarkdown;
  }

  const lines = ['# 深度调研', ''];
  const progress = Number(resultMap.progress || 0);
  lines.push(`进度：${Math.round(progress * 100)}%`);
  if (resultMap.status) lines.push(`状态：${resultMap.status}`);
  if (resultMap.evidenceCount !== undefined) lines.push(`证据：${resultMap.evidenceCount}`);
  if (resultMap.completedSections?.length) {
    lines.push('', '## 已完成章节', ...resultMap.completedSections.map((item) => `- ${item}`));
  }
  const branches = Object.values(resultMap.branches || {});
  if (branches.length) {
    lines.push(
      '',
      '## Researcher',
      ...branches.map(
        (branch) =>
          `- ${branch.nodeId || branch.role || 'researcher'}：${branch.status || 'running'}，证据 ${branch.evidenceCount || 0}`,
      ),
    );
  }
  if (resultMap.qualityStatus) {
    lines.push(
      '',
      '## 质量',
      `- 状态：${resultMap.qualityStatus}`,
      `- 来源：${resultMap.sourceCount || 0}`,
      `- 引用覆盖：${Math.round(Number(resultMap.citationCoverage || 0) * 100)}%`,
      `- 字数：${resultMap.charCount || 0}`,
    );
  }

  return lines.join('\n');
}

export const resolveMarkdownContent = (taskItem?: PanelItemType) => {
  let markDownContent = '';

  if (!taskItem) {
    return markDownContent;
  }

  const { messageType, toolResult, resultMap } = taskItem;

  switch (messageType) {
    case 'tool_result':
      markDownContent = toolResult?.toolResult || '';
      break;
    case 'tool_thought':
      // 兜底支持思考内容，避免异常状态下工作区出现“有标题但无内容”的空白面板。
      markDownContent = taskItem.toolThought || '';
      break;
    case 'tool_call':
      markDownContent = buildToolCallMarkdown(resultMap);
      break;
    case 'code':
      if (resultMap?.code || (resultMap?.codeOutput && resultMap?.isFinal)) {
        const text = resultMap?.code || resultMap?.codeOutput;
        markDownContent = `\`\`\`python\n${text}\n\`\`\``;
      }
      break;
    case 'markdown':
    case 'html':
      markDownContent = resultMap?.codeOutput || '';
      break;
    case 'data_analysis':
      markDownContent = resultMap?.codeOutput || '';
      break;
    case 'deep_research_progress':
    case 'deep_research_report':
      markDownContent = buildDeepResearchMarkdown(resultMap);
      break;
    case 'deep_search':
    case 'report':
      // 查询分解阶段还没有搜索结果文档，用 Markdown 先把待检索子查询展示出来。
      if (
        messageType === 'deep_search' &&
        resolveDeepSearchStage(resultMap?.messageType) === 'extend' &&
        !resultMap?.answer
      ) {
        markDownContent = buildDeepSearchExtendMarkdown(resultMap?.searchResult?.query);
        break;
      }
      markDownContent = resultMap.answer || '';
      break;
  }

  return markDownContent;
};

const useContent = (taskItem?: PanelItemType) => {
  const markDownContent = resolveMarkdownContent(taskItem);

  // let fileUrl = '';
  return {
    markDownContent,
    // fileUrl
  };
};

export default useContent;
