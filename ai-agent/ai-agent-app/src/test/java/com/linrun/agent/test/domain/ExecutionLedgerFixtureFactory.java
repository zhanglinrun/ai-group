package com.linrun.agent.test.domain;

import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.dto.File;
import com.linrun.agent.domain.agent.runtime.dto.tool.ToolCall;
import com.linrun.agent.domain.agent.runtime.tool.ToolCollection;
import com.linrun.agent.domain.agent.ledger.IExecutionLedgerReadRepository;
import com.linrun.agent.domain.agent.ledger.IExecutionLedgerWriteRepository;
import com.linrun.agent.domain.agent.ledger.AgentStreamEventStore;
import com.linrun.agent.domain.agent.ledger.entity.ArtifactRecord;
import com.linrun.agent.domain.agent.ledger.entity.DialogueSession;
import com.linrun.agent.domain.agent.ledger.entity.DialogueRun;
import com.linrun.agent.domain.agent.ledger.entity.LlmInvocation;
import com.linrun.agent.domain.agent.ledger.entity.ToolInvocation;
import com.linrun.agent.domain.agent.ledger.model.DialogueRunView;
import com.linrun.agent.domain.agent.ledger.model.DialogueRunRecoveryCommand;
import com.linrun.agent.domain.agent.ledger.model.DialogueSessionUpsertRecord;
import com.linrun.agent.domain.agent.ledger.model.DialogueSessionView;
import com.linrun.agent.domain.agent.ledger.model.ExecutionLedgerConstants;
import com.linrun.agent.domain.agent.ledger.model.LlmInvocationStartRecord;
import com.linrun.agent.domain.agent.ledger.model.ToolInvocationView;
import com.linrun.agent.domain.agent.ledger.model.tooloutput.ToolOutputPersistCommand;
import com.linrun.agent.domain.agent.ledger.model.tooloutput.ToolOutputView;
import com.linrun.agent.domain.agent.ledger.model.tooloutput.ToolStructuredOutput;
import com.linrun.agent.domain.agent.ledger.AgentExecutionRecorder;
import com.linrun.agent.domain.agent.ledger.ExecutionLedgerQueryService;
import com.linrun.agent.domain.agent.ledger.impl.AgentExecutionRecorderImpl;
import com.linrun.agent.domain.agent.ledger.impl.ExecutionLedgerQueryServiceImpl;
import com.linrun.agent.domain.agent.ledger.replay.ConversationHistoryReplayService;
import com.linrun.agent.domain.agent.ledger.replay.HistoryReplayPrinter;
import com.linrun.agent.domain.agent.ledger.replay.ReplayProjector;
import com.linrun.agent.domain.agent.ledger.replay.projector.ToolInvocationProjectorRegistry;
import com.linrun.agent.domain.agent.ledger.replay.projector.impl.CodeInterpreterToolInvocationProjector;
import com.linrun.agent.domain.agent.ledger.replay.projector.impl.DataAnalysisToolInvocationProjector;
import com.linrun.agent.domain.agent.ledger.replay.projector.impl.DefaultToolInvocationProjector;
import com.linrun.agent.domain.agent.ledger.replay.projector.impl.DeepSearchToolInvocationProjector;
import com.linrun.agent.domain.agent.ledger.replay.projector.impl.FileToolInvocationProjector;
import com.linrun.agent.domain.agent.ledger.replay.projector.impl.ImageGenerationToolInvocationProjector;
import com.linrun.agent.domain.agent.ledger.replay.projector.impl.MultiModalToolInvocationProjector;
import com.linrun.agent.domain.agent.ledger.replay.projector.impl.TodoWriteToolInvocationProjector;
import com.linrun.agent.domain.agent.ledger.replay.projector.impl.ReportToolInvocationProjector;
import com.linrun.agent.domain.agent.ledger.replay.projector.impl.ScriptRunnerToolInvocationProjector;
import com.linrun.agent.domain.agent.ledger.tooloutput.ToolOutputReader;
import com.linrun.agent.domain.agent.ledger.tooloutput.ToolOutputWriter;
import com.linrun.agent.infrastructure.adapter.repository.ExecutionLedgerReadRepository;
import com.linrun.agent.infrastructure.adapter.repository.ExecutionLedgerWriteRepository;
import com.linrun.agent.infrastructure.dao.reactor.IArtifactLedgerDao;
import com.linrun.agent.infrastructure.dao.reactor.IDialogueRunLedgerDao;
import com.linrun.agent.infrastructure.dao.reactor.IDialogueSessionLedgerDao;
import com.linrun.agent.infrastructure.dao.reactor.ILlmInvocationLedgerDao;
import com.linrun.agent.infrastructure.dao.reactor.IToolInvocationLedgerDao;
import org.springframework.dao.DuplicateKeyException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 执行账本测试夹具。
 * 使用内存 DAO 替代真实 MyBatis/数据库，便于在 application-test 禁库配置下做纯单元回归。
 */
public final class ExecutionLedgerFixtureFactory {

    private ExecutionLedgerFixtureFactory() {
    }

    static LedgerTestContext newLedgerTestContext() {
        InMemoryLedgerStore store = new InMemoryLedgerStore();
        InMemoryDialogueRunLedgerDao runDao = new InMemoryDialogueRunLedgerDao(store);
        InMemoryDialogueSessionLedgerDao sessionDao = new InMemoryDialogueSessionLedgerDao(store);
        InMemoryLlmInvocationLedgerDao llmDao = new InMemoryLlmInvocationLedgerDao(store);
        InMemoryToolInvocationLedgerDao toolDao = new InMemoryToolInvocationLedgerDao(store);
        InMemoryArtifactLedgerDao artifactDao = new InMemoryArtifactLedgerDao(store);
        InMemoryAgentStreamEventStore streamEventStore = new InMemoryAgentStreamEventStore(store);
        InMemoryToolOutputWriter toolOutputWriter = new InMemoryToolOutputWriter(store);
        InMemoryToolOutputReader toolOutputReader = new InMemoryToolOutputReader(store);
        ExecutionLedgerReadRepository readRepository = new ExecutionLedgerReadRepository(
                runDao, sessionDao, llmDao, toolDao, artifactDao
        );
        ExecutionLedgerWriteRepository writeRepository = new ExecutionLedgerWriteRepository(
                runDao, sessionDao, llmDao, toolDao, artifactDao
        );
        ExecutionLedgerQueryServiceImpl queryService = new ExecutionLedgerQueryServiceImpl(
                readRepository, toolOutputReader
        );
        ConversationHistoryReplayService replayService = new ConversationHistoryReplayService(
                queryService,
                new ReplayProjector(
                        new ToolInvocationProjectorRegistry(
                                List.of(
                                        new CodeInterpreterToolInvocationProjector(),
                                        new ReportToolInvocationProjector(),
                                        new DataAnalysisToolInvocationProjector(),
                                        new FileToolInvocationProjector(),
                                        new TodoWriteToolInvocationProjector(),
                                        new DeepSearchToolInvocationProjector(),
                                        new MultiModalToolInvocationProjector(),
                                        new ImageGenerationToolInvocationProjector(),
                                        new ScriptRunnerToolInvocationProjector(),
                                        new DefaultToolInvocationProjector()
                                ),
                                new DefaultToolInvocationProjector()
                        )
                ),
                new HistoryReplayPrinter(),
                streamEventStore
        );
        AgentExecutionRecorder recorder = new AgentExecutionRecorderImpl(
                writeRepository, toolOutputWriter
        );
        return new LedgerTestContext(
                store, recorder, queryService, replayService,
                readRepository, writeRepository, streamEventStore,
                runDao, sessionDao, llmDao, toolDao, artifactDao, toolOutputWriter, toolOutputReader
        );
    }

    static AgentContext newAgentContext(String requestId, String sessionId, AgentExecutionRecorder recorder) {
        ToolCollection toolCollection = new ToolCollection();
        AgentContext context = AgentContext.builder()
                .requestId(requestId)
                .sessionId(sessionId)
                .query("测试执行账本")
                .toolCollection(toolCollection)
                .productFiles(new ArrayList<>())
                .taskProductFiles(new ArrayList<>())
                .executionRecorder(recorder)
                .build();
        toolCollection.setAgentContext(context);
        return context;
    }

    static Long activateRun(AgentContext context, AgentExecutionRecorder recorder, String entryAgent) {
        return activateRun(context, recorder, entryAgent, null);
    }

    static Long activateRun(AgentContext context,
                            AgentExecutionRecorder recorder,
                            String entryAgent,
                            String ownerId) {
        Long runId = recorder.createRun(com.linrun.agent.domain.agent.ledger.model.DialogueRunStartRecord.builder()
                .runUid(context.getRequestId())
                .requestId(context.getRequestId())
                .sessionId(context.getSessionId())
                .ownerId(ownerId)
                .entryAgent(entryAgent)
                .queryText(context.getQuery())
                .startedAt(LocalDateTime.now())
                .build());
        context.activateLedgerRun(runId, context.getRequestId());
        return runId;
    }

    static Long createLlmInvocation(AgentContext context,
                                    AgentExecutionRecorder recorder,
                                    String agentName,
                                    Integer stepNo,
                                    String callKind) {
        context.markExecutionPosition(agentName, stepNo);
        Long llmInvocationId = recorder.createLlmInvocation(LlmInvocationStartRecord.builder()
                .runId(context.getAgentRunState().getRunId())
                .requestId(context.getRequestId())
                .invocationSeq(context.getAgentRunState().nextInvocationSeq())
                .agentName(agentName)
                .stepNo(stepNo)
                .callKind(callKind)
                .streaming(false)
                .modelName("test-model")
                .startedAt(LocalDateTime.now())
                .build());
        context.getAgentRunState().bindCurrentLlmInvocationId(llmInvocationId);
        return llmInvocationId;
    }

    static ToolCall newToolCall(String id, String toolName, String arguments) {
        return ToolCall.builder()
                .id(id)
                .type("function")
                .function(ToolCall.Function.builder()
                        .name(toolName)
                        .arguments(arguments)
                        .build())
                .build();
    }

    static File newFile(String fileName, String url, boolean internalFile) {
        return File.builder()
                .fileName(fileName)
                .ossUrl(url)
                .domainUrl(url)
                .isInternalFile(internalFile)
                .build();
    }

    static final class LedgerTestContext {
        final InMemoryLedgerStore store;
        final AgentExecutionRecorder recorder;
        final ExecutionLedgerQueryService queryService;
        final ConversationHistoryReplayService replayService;
        final ExecutionLedgerReadRepository readRepository;
        final ExecutionLedgerWriteRepository writeRepository;
        final AgentStreamEventStore streamEventStore;
        final IDialogueRunLedgerDao runDao;
        final IDialogueSessionLedgerDao sessionDao;
        final ILlmInvocationLedgerDao llmDao;
        final IToolInvocationLedgerDao toolDao;
        final IArtifactLedgerDao artifactDao;
        final ToolOutputWriter toolOutputWriter;
        final ToolOutputReader toolOutputReader;

        private LedgerTestContext(InMemoryLedgerStore store,
                                  AgentExecutionRecorder recorder,
                                  ExecutionLedgerQueryService queryService,
                                  ConversationHistoryReplayService replayService,
                                  ExecutionLedgerReadRepository readRepository,
                                  ExecutionLedgerWriteRepository writeRepository,
                                  AgentStreamEventStore streamEventStore,
                                  IDialogueRunLedgerDao runDao,
                                  IDialogueSessionLedgerDao sessionDao,
                                  ILlmInvocationLedgerDao llmDao,
                                  IToolInvocationLedgerDao toolDao,
                                  IArtifactLedgerDao artifactDao,
                                  ToolOutputWriter toolOutputWriter,
                                  ToolOutputReader toolOutputReader) {
            this.store = store;
            this.recorder = recorder;
            this.queryService = queryService;
            this.replayService = replayService;
            this.readRepository = readRepository;
            this.writeRepository = writeRepository;
            this.streamEventStore = streamEventStore;
            this.runDao = runDao;
            this.sessionDao = sessionDao;
            this.llmDao = llmDao;
            this.toolDao = toolDao;
            this.artifactDao = artifactDao;
            this.toolOutputWriter = toolOutputWriter;
            this.toolOutputReader = toolOutputReader;
        }
    }

    /**
     * 共享内存账本存储。
     */
    static final class InMemoryLedgerStore {
        long nextRunId = 1L;
        long nextSessionId = 1L;
        long nextLlmId = 1L;
        long nextToolId = 1L;
        long nextArtifactId = 1L;
        Map<Long, DialogueSession> sessions = new LinkedHashMap<>();
        Map<Long, DialogueRun> runs = new LinkedHashMap<>();
        Map<Long, LlmInvocation> llmInvocations = new LinkedHashMap<>();
        Map<Long, ToolInvocation> toolInvocations = new LinkedHashMap<>();
        Map<Long, ArtifactRecord> artifacts = new LinkedHashMap<>();
        Map<String, List<AgentStreamEventStore.StoredStreamEvent>> streamEventsByRequestId = new LinkedHashMap<>();
        Map<String, Map<Long, ToolOutputView>> toolOutputsByToolAndInvocationId = new LinkedHashMap<>();
        Map<String, Map<String, ToolOutputView>> toolOutputsByToolAndDirectKey = new LinkedHashMap<>();
    }

    static final class InMemoryAgentStreamEventStore implements AgentStreamEventStore {
        private final InMemoryLedgerStore store;

        private InMemoryAgentStreamEventStore(InMemoryLedgerStore store) {
            this.store = store;
        }

        @Override
        public void append(String requestId, String eventType, String eventJson) {
            List<StoredStreamEvent> events = store.streamEventsByRequestId
                    .computeIfAbsent(requestId, key -> new ArrayList<>());
            events.add(new StoredStreamEvent(events.size() + 1L, eventType, eventJson));
        }

        @Override
        public List<StoredStreamEvent> findByRequestId(String requestId) {
            return List.copyOf(store.streamEventsByRequestId.getOrDefault(requestId, List.of()));
        }
    }

    static final class InMemoryToolOutputWriter implements ToolOutputWriter {
        private final InMemoryLedgerStore store;

        private InMemoryToolOutputWriter(InMemoryLedgerStore store) {
            this.store = store;
        }

        @Override
        public void write(ToolOutputPersistCommand command) {
            writeInternal(command, false);
        }

        @Override
        public void writeOrThrow(ToolOutputPersistCommand command) {
            writeInternal(command, true);
        }

        private void writeInternal(ToolOutputPersistCommand command, boolean strict) {
            if (command == null || command.getStructuredOutput() == null) {
                return;
            }
            String toolName = resolveToolName(command);
            if (isBlank(toolName) || isBlank(command.getRequestId()) || isBlank(command.getToolCallId())) {
                return;
            }
            String directKey = buildDirectKey(command.getRequestId(), command.getToolCallId());
            Map<String, ToolOutputView> directOutputs = store.toolOutputsByToolAndDirectKey
                    .computeIfAbsent(toolName, key -> new LinkedHashMap<>());
            if (command.getToolInvocationId() != null) {
                Map<Long, ToolOutputView> invocationOutputs = store.toolOutputsByToolAndInvocationId
                        .computeIfAbsent(toolName, key -> new LinkedHashMap<>());
                if (invocationOutputs.containsKey(command.getToolInvocationId())) {
                    if (strict) {
                        throw new IllegalStateException("duplicate tool invocation id");
                    }
                    return;
                }
            }
            if (directOutputs.containsKey(directKey)) {
                if (strict) {
                    throw new IllegalStateException("duplicate request/toolCallId");
                }
                return;
            }
            ToolOutputView view = ToolOutputView.builder()
                    .toolName(toolName)
                    .requestId(command.getRequestId())
                    .requestSource(command.getRequestSource() == null ? ExecutionLedgerConstants.REQUEST_SOURCE_AGENT : command.getRequestSource())
                    .sessionId(command.getSessionId())
                    .toolCallId(command.getToolCallId())
                    .status(command.getStatus())
                    .errorMsg(command.getErrorMsg())
                    .createdAt(LocalDateTime.now())
                    .structuredOutput(command.getStructuredOutput())
                    .build();
            if (command.getToolInvocationId() != null) {
                store.toolOutputsByToolAndInvocationId
                        .computeIfAbsent(toolName, key -> new LinkedHashMap<>())
                        .put(command.getToolInvocationId(), cloneOutputView(view));
            }
            directOutputs.put(directKey, cloneOutputView(view));
        }

        private String resolveToolName(ToolOutputPersistCommand command) {
            if (command.getToolName() != null && !command.getToolName().isBlank()) {
                return command.getToolName();
            }
            return command.getStructuredOutput() == null ? null : command.getStructuredOutput().getToolName();
        }
    }

    static final class InMemoryToolOutputReader implements ToolOutputReader {
        private final InMemoryLedgerStore store;

        private InMemoryToolOutputReader(InMemoryLedgerStore store) {
            this.store = store;
        }

        @Override
        public java.util.Optional<ToolStructuredOutput> readByInvocationId(String toolName, Long toolInvocationId) {
            if (isBlank(toolName) || toolInvocationId == null) {
                return java.util.Optional.empty();
            }
            Map<Long, ToolOutputView> outputs = store.toolOutputsByToolAndInvocationId.get(toolName);
            ToolOutputView view = outputs == null ? null : outputs.get(toolInvocationId);
            return java.util.Optional.ofNullable(view == null ? null : view.getStructuredOutput());
        }

        @Override
        public java.util.Optional<ToolOutputView> readDirect(String requestId, String toolCallId) {
            if (isBlank(requestId) || isBlank(toolCallId)) {
                return java.util.Optional.empty();
            }
            String directKey = buildDirectKey(requestId, toolCallId);
            List<ToolOutputView> matches = new ArrayList<>();
            for (Map<String, ToolOutputView> outputs : store.toolOutputsByToolAndDirectKey.values()) {
                if (outputs == null) {
                    continue;
                }
                ToolOutputView view = outputs.get(directKey);
                if (view != null) {
                    matches.add(cloneOutputView(view));
                }
            }
            // direct lookup 命中多张输出表时视为冲突，不返回歧义结果。
            if (matches.size() != 1) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(matches.get(0));
        }
    }

    static final class InMemoryDialogueRunLedgerDao implements IDialogueRunLedgerDao {
        private final InMemoryLedgerStore store;

        private InMemoryDialogueRunLedgerDao(InMemoryLedgerStore store) {
            this.store = store;
        }

        @Override
        public synchronized int insertRun(DialogueRun run) {
            for (DialogueRun existing : store.runs.values()) {
                if (existing.getDeleted() == 0
                        && (existing.getRequestId().equals(run.getRequestId()) || existing.getRunUid().equals(run.getRunUid()))) {
                    throw new DuplicateKeyException("duplicate run key");
                }
            }
            run.setId(store.nextRunId++);
            run.setCreateTime(LocalDateTime.now());
            run.setUpdateTime(run.getCreateTime());
            run.setDeleted(0);
            store.runs.put(run.getId(), cloneRun(run));
            return 1;
        }

        @Override
        public int updateRunFinish(DialogueRun run) {
            DialogueRun existing = store.runs.get(run.getId());
            if (existing == null || existing.getDeleted() != 0 || existing.getStatus() != 0
                    || !java.util.Objects.equals(existing.getRequestId(), run.getRequestId())) {
                return 0;
            }
            existing.setStatus(run.getStatus());
            existing.setFinalSummaryText(run.getFinalSummaryText());
            existing.setLlmCallCount(run.getLlmCallCount());
            existing.setToolCallCount(run.getToolCallCount());
            existing.setArtifactCount(run.getArtifactCount());
            existing.setPromptTokensTotal(run.getPromptTokensTotal());
            existing.setCompletionTokensTotal(run.getCompletionTokensTotal());
            existing.setTotalTokensTotal(run.getTotalTokensTotal());
            existing.setErrorCode(run.getErrorCode());
            existing.setErrorMsg(run.getErrorMsg());
            existing.setFinishedAt(run.getFinishedAt());
            existing.setDurationMs(run.getDurationMs());
            existing.setUpdateTime(LocalDateTime.now());
            return 1;
        }

        @Override
        public synchronized int updateRunHeartbeat(Long runId, String requestId, LocalDateTime heartbeatAt) {
            DialogueRun existing = store.runs.get(runId);
            if (existing == null || existing.getDeleted() != 0
                    || existing.getStatus() != ExecutionLedgerConstants.STATUS_RUNNING
                    || !equalsNullable(existing.getRequestId(), requestId)) {
                return 0;
            }
            existing.setHeartbeatAt(heartbeatAt);
            existing.setUpdateTime(LocalDateTime.now());
            return 1;
        }

        @Override
        public synchronized int failWorkerLostRuns(DialogueRunRecoveryCommand command) {
            List<DialogueRun> candidates = store.runs.values().stream()
                    .filter(item -> item.getDeleted() == 0)
                    .filter(item -> item.getStatus() == ExecutionLedgerConstants.STATUS_RUNNING)
                    .filter(item -> item.getDeadlineAt() != null
                            && !item.getDeadlineAt().isAfter(command.deadlineBefore()))
                    .filter(item -> item.getHeartbeatAt() == null
                            || !item.getHeartbeatAt().isAfter(command.heartbeatBefore()))
                    .sorted(Comparator.comparing(DialogueRun::getDeadlineAt)
                            .thenComparing(DialogueRun::getId))
                    .limit(Math.max(1, command.batchLimit()))
                    .toList();
            for (DialogueRun run : candidates) {
                run.setStatus(command.terminalStatus());
                run.setErrorCode(command.errorCode());
                run.setErrorMsg(command.errorMsg());
                run.setFinishedAt(command.finishedAt());
                run.setDurationMs(duration(run.getStartedAt(), command.finishedAt()));
                run.setUpdateTime(LocalDateTime.now());
            }
            return candidates.size();
        }

        @Override
        public DialogueRun queryByRequestId(String requestId) {
            return store.runs.values().stream()
                    .filter(item -> item.getDeleted() == 0 && item.getRequestId().equals(requestId))
                    .findFirst()
                    .map(ExecutionLedgerFixtureFactory::cloneRun)
                    .orElse(null);
        }

        @Override
        public List<DialogueRunView> queryRecentBySessionId(String sessionId, int limit) {
            return store.runs.values().stream()
                    .filter(item -> item.getDeleted() == 0 && item.getSessionId().equals(sessionId))
                    .sorted(Comparator.comparing(DialogueRun::getCreateTime, Comparator.reverseOrder())
                            .thenComparing(DialogueRun::getId, Comparator.reverseOrder()))
                    .limit(limit)
                    .map(ExecutionLedgerFixtureFactory::toRunView)
                    .toList();
        }

        @Override
        public List<DialogueRunView> queryBySessionId(String sessionId) {
            return store.runs.values().stream()
                    .filter(item -> item.getDeleted() == 0 && item.getSessionId().equals(sessionId))
                    .sorted(Comparator.comparing(DialogueRun::getCreateTime)
                            .thenComparing(DialogueRun::getId))
                    .map(ExecutionLedgerFixtureFactory::toRunView)
                    .toList();
        }
    }

    static final class InMemoryDialogueSessionLedgerDao implements IDialogueSessionLedgerDao {
        private final InMemoryLedgerStore store;

        private InMemoryDialogueSessionLedgerDao(InMemoryLedgerStore store) {
            this.store = store;
        }

        @Override
        public int upsertSession(DialogueSessionUpsertRecord record) {
            if (record == null || isBlank(record.getSessionId())) {
                return 0;
            }
            DialogueSession session = store.sessions.values().stream()
                    .filter(item -> item.getDeleted() == 0 && record.getSessionId().equals(item.getSessionId()))
                    .findFirst()
                    .orElse(null);
            if (session == null) {
                session = DialogueSession.builder()
                        .id(store.nextSessionId++)
                        .sessionId(record.getSessionId())
                        .createTime(LocalDateTime.now())
                        .deleted(0)
                        .build();
                store.sessions.put(session.getId(), session);
            }
            if (isBlank(session.getOwnerId())) {
                session.setOwnerId(record.getOwnerId());
            }
            session.setTitle(record.getTitle());
            session.setStatus(record.getStatus());
            session.setLatestRequestId(record.getLatestRequestId());
            session.setLatestQueryText(record.getLatestQueryText());
            session.setLatestSummaryText(record.getLatestSummaryText());
            session.setRunCount(record.getRunCount());
            session.setFinishedRunCount(record.getFinishedRunCount());
            session.setFailedRunCount(record.getFailedRunCount());
            session.setStartedAt(record.getStartedAt());
            session.setLastActiveAt(record.getLastActiveAt());
            session.setUpdateTime(LocalDateTime.now());
            return 1;
        }

        @Override
        public synchronized int reconcileWorkerLostSessionHeads(String errorCode) {
            int updated = 0;
            for (DialogueSession session : store.sessions.values()) {
                if (session.getDeleted() != 0
                        || session.getStatus() == null
                        || session.getStatus() != ExecutionLedgerConstants.STATUS_RUNNING) {
                    continue;
                }
                DialogueRun latestRun = store.runs.values().stream()
                        .filter(item -> item.getDeleted() == 0
                                && equalsNullable(session.getSessionId(), item.getSessionId()))
                        .max(Comparator
                                .comparing((DialogueRun item) -> item.getStartedAt() == null
                                                ? item.getCreateTime() : item.getStartedAt(),
                                        Comparator.nullsFirst(Comparator.naturalOrder()))
                                .thenComparing(DialogueRun::getId))
                        .orElse(null);
                if (latestRun == null
                        || !equalsNullable(errorCode, latestRun.getErrorCode())
                        || (latestRun.getStatus() != ExecutionLedgerConstants.STATUS_FAILED
                            && latestRun.getStatus() != ExecutionLedgerConstants.STATUS_TIMEOUT
                            && latestRun.getStatus() != ExecutionLedgerConstants.STATUS_STOPPED)) {
                    continue;
                }
                List<DialogueRun> sessionRuns = store.runs.values().stream()
                        .filter(item -> item.getDeleted() == 0
                                && equalsNullable(session.getSessionId(), item.getSessionId()))
                        .toList();
                session.setStatus(latestRun.getStatus());
                session.setLatestRequestId(latestRun.getRequestId());
                session.setLatestQueryText(latestRun.getQueryText());
                session.setLatestSummaryText(latestRun.getFinalSummaryText());
                session.setRunCount(sessionRuns.size());
                session.setFinishedRunCount((int) sessionRuns.stream()
                        .filter(item -> item.getStatus() == ExecutionLedgerConstants.STATUS_SUCCESS)
                        .count());
                session.setFailedRunCount((int) sessionRuns.stream()
                        .filter(item -> item.getStatus() == ExecutionLedgerConstants.STATUS_FAILED
                                || item.getStatus() == ExecutionLedgerConstants.STATUS_TIMEOUT
                                || item.getStatus() == ExecutionLedgerConstants.STATUS_STOPPED)
                        .count());
                if (session.getStartedAt() == null) {
                    session.setStartedAt(latestRun.getStartedAt());
                }
                session.setLastActiveAt(latestRun.getFinishedAt() == null
                        ? latestRun.getStartedAt() : latestRun.getFinishedAt());
                session.setUpdateTime(LocalDateTime.now());
                updated++;
            }
            return updated;
        }

        @Override
        public int softDeleteSession(String ownerId, String sessionId) {
            DialogueSession session = store.sessions.values().stream()
                    .filter(item -> item.getDeleted() == 0
                            && equalsNullable(ownerId, item.getOwnerId())
                            && sessionId.equals(item.getSessionId()))
                    .findFirst()
                    .orElse(null);
            if (session == null) {
                return 0;
            }
            session.setDeleted(1);
            session.setUpdateTime(LocalDateTime.now());
            return 1;
        }

        @Override
        public DialogueSession queryBySessionId(String sessionId) {
            return store.sessions.values().stream()
                    .filter(item -> item.getDeleted() == 0 && item.getSessionId().equals(sessionId))
                    .findFirst()
                    .map(ExecutionLedgerFixtureFactory::cloneSession)
                    .orElse(null);
        }

        @Override
        public DialogueSessionView querySessionView(String sessionId) {
            DialogueSession session = queryBySessionId(sessionId);
            return session == null ? null : toSessionView(session);
        }

        @Override
        public List<DialogueSessionView> queryRecentSessions(int limit) {
            return store.sessions.values().stream()
                    .filter(item -> item.getDeleted() == 0)
                    .sorted(Comparator.comparing(DialogueSession::getLastActiveAt, Comparator.nullsLast(Comparator.reverseOrder()))
                            .thenComparing(DialogueSession::getId, Comparator.reverseOrder()))
                    .limit(limit)
                    .map(ExecutionLedgerFixtureFactory::toSessionView)
                    .toList();
        }

        @Override
        public DialogueSessionView querySessionViewByOwner(String ownerId, String sessionId) {
            DialogueSession session = queryBySessionId(sessionId);
            if (session == null || !equalsNullable(ownerId, session.getOwnerId())) {
                return null;
            }
            return toSessionView(session);
        }

        @Override
        public List<DialogueSessionView> queryRecentSessionsByOwner(String ownerId, int limit) {
            return store.sessions.values().stream()
                    .filter(item -> item.getDeleted() == 0 && equalsNullable(ownerId, item.getOwnerId()))
                    .sorted(Comparator.comparing(DialogueSession::getLastActiveAt, Comparator.nullsLast(Comparator.reverseOrder()))
                            .thenComparing(DialogueSession::getId, Comparator.reverseOrder()))
                    .limit(limit)
                    .map(ExecutionLedgerFixtureFactory::toSessionView)
                    .toList();
        }
    }

    static final class InMemoryLlmInvocationLedgerDao implements ILlmInvocationLedgerDao {
        private final InMemoryLedgerStore store;

        private InMemoryLlmInvocationLedgerDao(InMemoryLedgerStore store) {
            this.store = store;
        }

        @Override
        public synchronized int insertLlmInvocation(LlmInvocation invocation) {
            for (LlmInvocation existing : store.llmInvocations.values()) {
                if (existing.getDeleted() == 0
                        && existing.getRunId().equals(invocation.getRunId())
                        && existing.getInvocationSeq().equals(invocation.getInvocationSeq())) {
                    throw new IllegalStateException("duplicate llm invocation seq");
                }
            }
            invocation.setId(store.nextLlmId++);
            invocation.setCreateTime(LocalDateTime.now());
            invocation.setUpdateTime(invocation.getCreateTime());
            invocation.setDeleted(0);
            store.llmInvocations.put(invocation.getId(), cloneLlm(invocation));
            return 1;
        }

        @Override
        public int updateLlmInvocationFinish(LlmInvocation invocation) {
            LlmInvocation existing = store.llmInvocations.get(invocation.getId());
            if (existing == null) {
                return 0;
            }
            existing.setStatus(invocation.getStatus());
            existing.setResponseText(invocation.getResponseText());
            existing.setToolCallCount(invocation.getToolCallCount());
            existing.setPromptTokens(invocation.getPromptTokens());
            existing.setCompletionTokens(invocation.getCompletionTokens());
            existing.setTotalTokens(invocation.getTotalTokens());
            existing.setUsageSource(invocation.getUsageSource());
            existing.setChargedMicrocredits(invocation.getChargedMicrocredits());
            existing.setFinishReason(invocation.getFinishReason());
            existing.setErrorMsg(invocation.getErrorMsg());
            existing.setFinishedAt(invocation.getFinishedAt());
            existing.setDurationMs(duration(existing.getStartedAt(), invocation.getFinishedAt()));
            existing.setUpdateTime(LocalDateTime.now());
            return 1;
        }

        @Override
        public List<LlmInvocation> queryByRunId(Long runId) {
            return store.llmInvocations.values().stream()
                    .filter(item -> item.getDeleted() == 0 && item.getRunId().equals(runId))
                    .sorted(Comparator.comparing(LlmInvocation::getInvocationSeq).thenComparing(LlmInvocation::getId))
                    .map(ExecutionLedgerFixtureFactory::cloneLlm)
                    .toList();
        }

        @Override
        public List<LlmInvocation> queryByRunIds(List<Long> runIds) {
            return store.llmInvocations.values().stream()
                    .filter(item -> item.getDeleted() == 0 && runIds.contains(item.getRunId()))
                    .sorted(Comparator.comparing(LlmInvocation::getRunId)
                            .thenComparing(LlmInvocation::getInvocationSeq)
                            .thenComparing(LlmInvocation::getId))
                    .map(ExecutionLedgerFixtureFactory::cloneLlm)
                    .toList();
        }
    }

    static final class InMemoryToolInvocationLedgerDao implements IToolInvocationLedgerDao {
        private final InMemoryLedgerStore store;

        private InMemoryToolInvocationLedgerDao(InMemoryLedgerStore store) {
            this.store = store;
        }

        @Override
        public synchronized int insertToolInvocation(ToolInvocation invocation) {
            for (ToolInvocation existing : store.toolInvocations.values()) {
                if (existing.getDeleted() != 0) {
                    continue;
                }
                if (existing.getRunId().equals(invocation.getRunId())
                        && existing.getToolCallId().equals(invocation.getToolCallId())) {
                    throw new IllegalStateException("duplicate run/toolCallId");
                }
                if (existing.getLlmInvocationId().equals(invocation.getLlmInvocationId())
                        && existing.getDispatchIndex().equals(invocation.getDispatchIndex())) {
                    throw new IllegalStateException("duplicate llm/dispatchIndex");
                }
            }
            invocation.setId(store.nextToolId++);
            invocation.setCreateTime(LocalDateTime.now());
            invocation.setUpdateTime(invocation.getCreateTime());
            invocation.setDeleted(0);
            store.toolInvocations.put(invocation.getId(), cloneTool(invocation));
            return 1;
        }

        @Override
        public int updateToolInvocationFinish(ToolInvocation invocation) {
            ToolInvocation existing = store.toolInvocations.get(invocation.getId());
            if (existing == null) {
                return 0;
            }
            existing.setStatus(invocation.getStatus());
            existing.setToolResult(invocation.getToolResult());
            existing.setLlmObservation(invocation.getLlmObservation());
            existing.setErrorMsg(invocation.getErrorMsg());
            existing.setFinishedAt(invocation.getFinishedAt());
            existing.setDurationMs(duration(existing.getStartedAt(), invocation.getFinishedAt()));
            existing.setUpdateTime(LocalDateTime.now());
            return 1;
        }

        @Override
        public List<ToolInvocation> queryByRunId(Long runId) {
            return store.toolInvocations.values().stream()
                    .filter(item -> item.getDeleted() == 0 && item.getRunId().equals(runId))
                    .sorted(Comparator.comparing(ToolInvocation::getLlmInvocationId)
                            .thenComparing(ToolInvocation::getDispatchIndex)
                            .thenComparing(ToolInvocation::getId))
                    .map(ExecutionLedgerFixtureFactory::cloneTool)
                    .toList();
        }

        @Override
        public List<ToolInvocation> queryByRunIds(List<Long> runIds) {
            return store.toolInvocations.values().stream()
                    .filter(item -> item.getDeleted() == 0 && runIds.contains(item.getRunId()))
                    .sorted(Comparator.comparing(ToolInvocation::getRunId)
                            .thenComparing(ToolInvocation::getLlmInvocationId)
                            .thenComparing(ToolInvocation::getDispatchIndex)
                            .thenComparing(ToolInvocation::getId))
                    .map(ExecutionLedgerFixtureFactory::cloneTool)
                    .toList();
        }

        @Override
        public List<ToolInvocation> queryByLlmInvocationIds(List<Long> llmInvocationIds) {
            return store.toolInvocations.values().stream()
                    .filter(item -> item.getDeleted() == 0 && llmInvocationIds.contains(item.getLlmInvocationId()))
                    .sorted(Comparator.comparing(ToolInvocation::getLlmInvocationId)
                            .thenComparing(ToolInvocation::getDispatchIndex)
                            .thenComparing(ToolInvocation::getId))
                    .map(ExecutionLedgerFixtureFactory::cloneTool)
                    .toList();
        }

        @Override
        public List<ToolInvocationView> queryRecentByToolName(String toolName, int limit) {
            return store.toolInvocations.values().stream()
                    .filter(item -> item.getDeleted() == 0 && item.getToolName().equals(toolName))
                    .sorted(Comparator.comparing(ToolInvocation::getCreateTime, Comparator.reverseOrder())
                            .thenComparing(ToolInvocation::getId, Comparator.reverseOrder()))
                    .limit(limit)
                    .map(item -> {
                        DialogueRun run = store.runs.get(item.getRunId());
                        int artifactCount = (int) store.artifacts.values().stream()
                                .filter(artifact -> artifact.getDeleted() == 0 && item.getId().equals(artifact.getToolInvocationId()))
                                .count();
                        return ToolInvocationView.builder()
                                .id(item.getId())
                                .runId(item.getRunId())
                                .llmInvocationId(item.getLlmInvocationId())
                                .requestId(run == null ? null : run.getRequestId())
                                .sessionId(run == null ? null : run.getSessionId())
                                .toolCallId(item.getToolCallId())
                                .dispatchIndex(item.getDispatchIndex())
                                .agentName(item.getAgentName())
                                .stepNo(item.getStepNo())
                                .toolName(item.getToolName())
                                .toolProvider(item.getToolProvider())
                                .inputJson(item.getInputJson())
                                .toolResult(item.getToolResult())
                                .llmObservation(item.getLlmObservation())
                                .status(item.getStatus())
                                .errorMsg(item.getErrorMsg())
                                .durationMs(item.getDurationMs())
                                .artifactCount(artifactCount)
                                .startedAt(item.getStartedAt())
                                .finishedAt(item.getFinishedAt())
                                .createTime(item.getCreateTime())
                                .build();
                    })
                    .toList();
        }
    }

    static final class InMemoryArtifactLedgerDao implements IArtifactLedgerDao {
        private final InMemoryLedgerStore store;

        private InMemoryArtifactLedgerDao(InMemoryLedgerStore store) {
            this.store = store;
        }

        @Override
        public synchronized int batchInsertArtifacts(List<ArtifactRecord> records) {
            int inserted = 0;
            for (ArtifactRecord record : records) {
                boolean exists = store.artifacts.values().stream()
                        .anyMatch(existing -> existing.getDeleted() == 0
                                && equalsNullable(existing.getRequestId(), record.getRequestId())
                                && equalsNullable(existing.getToolCallId(), record.getToolCallId())
                                && equalsNullable(existing.getStorageKey(), record.getStorageKey()));
                if (exists) {
                    continue;
                }
                record.setId(store.nextArtifactId++);
                record.setCreateTime(LocalDateTime.now());
                record.setUpdateTime(record.getCreateTime());
                record.setDeleted(0);
                store.artifacts.put(record.getId(), cloneArtifact(record));
                inserted++;
            }
            return inserted;
        }

        @Override
        public List<ArtifactRecord> queryByRunId(Long runId) {
            return store.artifacts.values().stream()
                    .filter(item -> item.getDeleted() == 0 && item.getRunId().equals(runId))
                    .sorted(Comparator.comparing(ArtifactRecord::getCreateTime).thenComparing(ArtifactRecord::getId))
                    .map(ExecutionLedgerFixtureFactory::cloneArtifact)
                    .toList();
        }

        @Override
        public List<ArtifactRecord> queryByRunIds(List<Long> runIds) {
            return store.artifacts.values().stream()
                    .filter(item -> item.getDeleted() == 0 && runIds.contains(item.getRunId()))
                    .sorted(Comparator.comparing(ArtifactRecord::getRunId).reversed()
                            .thenComparing(ArtifactRecord::getCreateTime)
                            .thenComparing(ArtifactRecord::getId))
                    .map(ExecutionLedgerFixtureFactory::cloneArtifact)
                    .toList();
        }

        @Override
        public List<ArtifactRecord> queryByToolInvocationIds(List<Long> toolInvocationIds) {
            return store.artifacts.values().stream()
                    .filter(item -> item.getDeleted() == 0
                            && item.getToolInvocationId() != null
                            && toolInvocationIds.contains(item.getToolInvocationId())
                            && ExecutionLedgerConstants.ARTIFACT_ROLE_OUTPUT.equals(item.getArtifactRole())
                            && ExecutionLedgerConstants.VISIBILITY_VISIBLE.equals(item.getVisibility()))
                    .sorted(Comparator.comparing(ArtifactRecord::getToolInvocationId)
                            .thenComparing(ArtifactRecord::getCreateTime)
                            .thenComparing(ArtifactRecord::getId))
                    .map(ExecutionLedgerFixtureFactory::cloneArtifact)
                    .toList();
        }

        @Override
        public List<ArtifactRecord> queryInputArtifactsByRunIds(List<Long> runIds) {
            return store.artifacts.values().stream()
                    .filter(item -> item.getDeleted() == 0
                            && runIds.contains(item.getRunId())
                            && ExecutionLedgerConstants.ARTIFACT_ROLE_INPUT.equals(item.getArtifactRole())
                            && ExecutionLedgerConstants.VISIBILITY_VISIBLE.equals(item.getVisibility()))
                    .sorted(Comparator.comparing(ArtifactRecord::getRunId)
                            .thenComparing(ArtifactRecord::getCreateTime)
                            .thenComparing(ArtifactRecord::getId))
                    .map(ExecutionLedgerFixtureFactory::cloneArtifact)
                    .toList();
        }

        @Override
        public List<ArtifactRecord> queryOutputArtifactsByToolInvocationId(Long toolInvocationId) {
            return store.artifacts.values().stream()
                    .filter(item -> item.getDeleted() == 0
                            && toolInvocationId != null
                            && toolInvocationId.equals(item.getToolInvocationId())
                            && ExecutionLedgerConstants.ARTIFACT_ROLE_OUTPUT.equals(item.getArtifactRole())
                            && ExecutionLedgerConstants.VISIBILITY_VISIBLE.equals(item.getVisibility()))
                    .sorted(Comparator.comparing(ArtifactRecord::getCreateTime).thenComparing(ArtifactRecord::getId))
                    .map(ExecutionLedgerFixtureFactory::cloneArtifact)
                    .toList();
        }

        @Override
        public List<ArtifactRecord> queryOutputArtifactsByRunIdAndToolCallId(Long runId, String toolCallId) {
            return store.artifacts.values().stream()
                    .filter(item -> item.getDeleted() == 0
                            && runId != null
                            && runId.equals(item.getRunId())
                            && equalsNullable(toolCallId, item.getToolCallId())
                            && ExecutionLedgerConstants.ARTIFACT_ROLE_OUTPUT.equals(item.getArtifactRole())
                            && ExecutionLedgerConstants.VISIBILITY_VISIBLE.equals(item.getVisibility()))
                    .sorted(Comparator.comparing(ArtifactRecord::getCreateTime).thenComparing(ArtifactRecord::getId))
                    .map(ExecutionLedgerFixtureFactory::cloneArtifact)
                    .toList();
        }

        @Override
        public List<ArtifactRecord> queryOutputArtifactsByRequestIdAndToolCallId(String requestId, String toolCallId) {
            return store.artifacts.values().stream()
                    .filter(item -> item.getDeleted() == 0
                            && equalsNullable(requestId, item.getRequestId())
                            && equalsNullable(toolCallId, item.getToolCallId())
                            && ExecutionLedgerConstants.ARTIFACT_ROLE_OUTPUT.equals(item.getArtifactRole())
                            && ExecutionLedgerConstants.VISIBILITY_VISIBLE.equals(item.getVisibility()))
                    .sorted(Comparator.comparing(ArtifactRecord::getCreateTime).thenComparing(ArtifactRecord::getId))
                    .map(ExecutionLedgerFixtureFactory::cloneArtifact)
                    .toList();
        }
    }

    private static boolean equalsNullable(Object left, Object right) {
        if (left == null) {
            return right == null;
        }
        return left.equals(right);
    }

    private static long duration(LocalDateTime startedAt, LocalDateTime finishedAt) {
        if (startedAt == null || finishedAt == null) {
            return 0L;
        }
        return Duration.between(startedAt, finishedAt).toMillis();
    }

    private static DialogueRun cloneRun(DialogueRun run) {
        return DialogueRun.builder()
                .id(run.getId())
                .runUid(run.getRunUid())
                .requestId(run.getRequestId())
                .sessionId(run.getSessionId())
                .ownerId(run.getOwnerId())
                .entryAgent(run.getEntryAgent())
                .roleAgentId(run.getRoleAgentId())
                .roleAgentName(run.getRoleAgentName())
                .status(run.getStatus())
                .queryText(run.getQueryText())
                .requestFingerprint(run.getRequestFingerprint())
                .finalSummaryText(run.getFinalSummaryText())
                .llmCallCount(run.getLlmCallCount())
                .toolCallCount(run.getToolCallCount())
                .artifactCount(run.getArtifactCount())
                .promptTokensTotal(run.getPromptTokensTotal())
                .completionTokensTotal(run.getCompletionTokensTotal())
                .totalTokensTotal(run.getTotalTokensTotal())
                .errorCode(run.getErrorCode())
                .errorMsg(run.getErrorMsg())
                .startedAt(run.getStartedAt())
                .deadlineAt(run.getDeadlineAt())
                .heartbeatAt(run.getHeartbeatAt())
                .finishedAt(run.getFinishedAt())
                .durationMs(run.getDurationMs())
                .createTime(run.getCreateTime())
                .updateTime(run.getUpdateTime())
                .deleted(run.getDeleted())
                .build();
    }

    private static DialogueSession cloneSession(DialogueSession session) {
        return DialogueSession.builder()
                .id(session.getId())
                .sessionId(session.getSessionId())
                .ownerId(session.getOwnerId())
                .title(session.getTitle())
                .status(session.getStatus())
                .latestRequestId(session.getLatestRequestId())
                .latestQueryText(session.getLatestQueryText())
                .latestSummaryText(session.getLatestSummaryText())
                .runCount(session.getRunCount())
                .finishedRunCount(session.getFinishedRunCount())
                .failedRunCount(session.getFailedRunCount())
                .startedAt(session.getStartedAt())
                .lastActiveAt(session.getLastActiveAt())
                .createTime(session.getCreateTime())
                .updateTime(session.getUpdateTime())
                .deleted(session.getDeleted())
                .build();
    }

    private static LlmInvocation cloneLlm(LlmInvocation invocation) {
        return LlmInvocation.builder()
                .id(invocation.getId())
                .runId(invocation.getRunId())
                .invocationSeq(invocation.getInvocationSeq())
                .agentName(invocation.getAgentName())
                .stepNo(invocation.getStepNo())
                .callKind(invocation.getCallKind())
                .streaming(invocation.getStreaming())
                .modelName(invocation.getModelName())
                .responseText(invocation.getResponseText())
                .toolCallCount(invocation.getToolCallCount())
                .promptTokens(invocation.getPromptTokens())
                .completionTokens(invocation.getCompletionTokens())
                .totalTokens(invocation.getTotalTokens())
                .inputRateSnapshot(invocation.getInputRateSnapshot())
                .outputRateSnapshot(invocation.getOutputRateSnapshot())
                .usageSource(invocation.getUsageSource())
                .chargedMicrocredits(invocation.getChargedMicrocredits())
                .finishReason(invocation.getFinishReason())
                .status(invocation.getStatus())
                .errorMsg(invocation.getErrorMsg())
                .startedAt(invocation.getStartedAt())
                .finishedAt(invocation.getFinishedAt())
                .durationMs(invocation.getDurationMs())
                .createTime(invocation.getCreateTime())
                .updateTime(invocation.getUpdateTime())
                .deleted(invocation.getDeleted())
                .build();
    }

    private static ToolInvocation cloneTool(ToolInvocation invocation) {
        return ToolInvocation.builder()
                .id(invocation.getId())
                .runId(invocation.getRunId())
                .llmInvocationId(invocation.getLlmInvocationId())
                .toolCallId(invocation.getToolCallId())
                .dispatchIndex(invocation.getDispatchIndex())
                .agentName(invocation.getAgentName())
                .stepNo(invocation.getStepNo())
                .toolName(invocation.getToolName())
                .toolProvider(invocation.getToolProvider())
                .inputJson(invocation.getInputJson())
                .toolResult(invocation.getToolResult())
                .llmObservation(invocation.getLlmObservation())
                .status(invocation.getStatus())
                .errorMsg(invocation.getErrorMsg())
                .startedAt(invocation.getStartedAt())
                .finishedAt(invocation.getFinishedAt())
                .durationMs(invocation.getDurationMs())
                .createTime(invocation.getCreateTime())
                .updateTime(invocation.getUpdateTime())
                .deleted(invocation.getDeleted())
                .build();
    }

    private static ArtifactRecord cloneArtifact(ArtifactRecord artifact) {
        return ArtifactRecord.builder()
                .id(artifact.getId())
                .runId(artifact.getRunId())
                .requestId(artifact.getRequestId())
                .toolInvocationId(artifact.getToolInvocationId())
                .toolCallId(artifact.getToolCallId())
                .artifactRole(artifact.getArtifactRole())
                .visibility(artifact.getVisibility())
                .sourceType(artifact.getSourceType())
                .sourceName(artifact.getSourceName())
                .fileName(artifact.getFileName())
                .storageKey(artifact.getStorageKey())
                .downloadUrl(artifact.getDownloadUrl())
                .previewUrl(artifact.getPreviewUrl())
                .mimeType(artifact.getMimeType())
                .fileSize(artifact.getFileSize())
                .fileHash(artifact.getFileHash())
                .metadataJson(artifact.getMetadataJson())
                .createTime(artifact.getCreateTime())
                .updateTime(artifact.getUpdateTime())
                .deleted(artifact.getDeleted())
                .build();
    }

    private static ToolOutputView cloneOutputView(ToolOutputView view) {
        if (view == null) {
            return null;
        }
        return ToolOutputView.builder()
                .toolName(view.getToolName())
                .requestId(view.getRequestId())
                .requestSource(view.getRequestSource())
                .sessionId(view.getSessionId())
                .toolCallId(view.getToolCallId())
                .status(view.getStatus())
                .errorMsg(view.getErrorMsg())
                .createdAt(view.getCreatedAt())
                .structuredOutput(view.getStructuredOutput())
                .build();
    }

    private static String buildDirectKey(String requestId, String toolCallId) {
        return String.valueOf(requestId) + "||" + String.valueOf(toolCallId);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static DialogueRunView toRunView(DialogueRun run) {
        return DialogueRunView.builder()
                .id(run.getId())
                .runUid(run.getRunUid())
                .requestId(run.getRequestId())
                .sessionId(run.getSessionId())
                .ownerId(run.getOwnerId())
                .entryAgent(run.getEntryAgent())
                .roleAgentId(run.getRoleAgentId())
                .roleAgentName(run.getRoleAgentName())
                .status(run.getStatus())
                .queryText(run.getQueryText())
                .finalSummaryText(run.getFinalSummaryText())
                .llmCallCount(run.getLlmCallCount())
                .toolCallCount(run.getToolCallCount())
                .artifactCount(run.getArtifactCount())
                .promptTokensTotal(run.getPromptTokensTotal())
                .completionTokensTotal(run.getCompletionTokensTotal())
                .totalTokensTotal(run.getTotalTokensTotal())
                .errorCode(run.getErrorCode())
                .errorMsg(run.getErrorMsg())
                .startedAt(run.getStartedAt())
                .finishedAt(run.getFinishedAt())
                .durationMs(run.getDurationMs())
                .createTime(run.getCreateTime())
                .build();
    }

    private static DialogueSessionView toSessionView(DialogueSession session) {
        return DialogueSessionView.builder()
                .id(session.getId())
                .sessionId(session.getSessionId())
                .ownerId(session.getOwnerId())
                .title(session.getTitle())
                .status(session.getStatus())
                .latestRequestId(session.getLatestRequestId())
                .latestQueryText(session.getLatestQueryText())
                .latestSummaryText(session.getLatestSummaryText())
                .runCount(session.getRunCount())
                .finishedRunCount(session.getFinishedRunCount())
                .failedRunCount(session.getFailedRunCount())
                .startedAt(session.getStartedAt())
                .lastActiveAt(session.getLastActiveAt())
                .createTime(session.getCreateTime())
                .build();
    }
}
