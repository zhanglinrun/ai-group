package com.linrun.agent.trigger.http.agent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Value;
import com.linrun.agent.domain.agent.adapter.port.QuotaBillingPort;
import com.linrun.agent.domain.agent.adapter.port.QuotaInsufficientException;
import com.linrun.agent.domain.agent.quota.QuotaProviderAlreadyStartedException;
import com.linrun.agent.api.response.Response;
import com.linrun.agent.domain.agent.reactor.model.imagegeneration.WorkspaceImageFile;
import com.linrun.agent.domain.agent.reactor.model.imagegeneration.WorkspaceImageGenerationCommand;
import com.linrun.agent.domain.agent.reactor.model.imagegeneration.WorkspaceImageGenerationHistoryBatch;
import com.linrun.agent.domain.agent.reactor.model.imagegeneration.WorkspaceImageGenerationHistoryPage;
import com.linrun.agent.domain.agent.reactor.model.imagegeneration.WorkspaceImageGenerationResult;
import com.linrun.agent.domain.agent.reactor.service.IWorkspaceImageGenerationService;
import com.linrun.agent.trigger.http.agent.vo.PageRespVO;
import com.linrun.agent.trigger.http.agent.vo.WorkspaceImageFileRespVO;
import com.linrun.agent.trigger.http.agent.vo.WorkspaceImageGenerationReqVO;
import com.linrun.agent.trigger.http.agent.vo.WorkspaceImageGenerationRespVO;
import com.linrun.agent.trigger.http.agent.vo.WorkspaceImageHistoryBatchRespVO;
import com.linrun.agent.types.agent.owner.OwnerRequestContext;
import com.linrun.agent.types.enums.ResponseCode;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 生图工作台接口。
 */
@Slf4j
@RestController
@RequestMapping("/api/agent/image-generation")
@RequiredArgsConstructor
public class AgentImageGenerationController {

    private final IWorkspaceImageGenerationService workspaceImageGenerationService;
    private final QuotaBillingPort memberQuotaBillingService;

    @Value("${ai-group.billing.tools.image-generation-microcredits:1000000}")
    private long imageGenerationMicrocredits = 1_000_000L;

    @PostMapping("/generate")
    public Response<WorkspaceImageGenerationRespVO> generate(@RequestBody WorkspaceImageGenerationReqVO reqVO) {
        String freezeId = null;
        try {
            if (reqVO == null) {
                throw new IllegalArgumentException("请求体不能为空");
            }
            if (reqVO.getPrompt() == null || reqVO.getPrompt().isBlank()) {
                throw new IllegalArgumentException("prompt不能为空");
            }
            if (reqVO.getRequestId() == null || reqVO.getRequestId().isBlank()) {
                throw new IllegalArgumentException("requestId不能为空");
            }
            Long userId = OwnerRequestContext.requireOwnerId();
            long surcharge = Math.max(0L, imageGenerationMicrocredits);
            if (surcharge > 0) {
                QuotaBillingPort.Reservation reservation = memberQuotaBillingService.reserve(
                        userId, surcharge, surcharge, "image_generation",
                        reqVO.getRequestId() + ":tool:image_generation");
                freezeId = reservation.freezeId();
                try {
                    memberQuotaBillingService.markProviderStarted(freezeId);
                } catch (QuotaProviderAlreadyStartedException duplicateAdmission) {
                    freezeId = null;
                    throw duplicateAdmission;
                }
            }
            WorkspaceImageGenerationResult result = workspaceImageGenerationService.generate(
                    WorkspaceImageGenerationCommand.builder()
                            .requestId(reqVO.getRequestId())
                            .ownerId(userId)
                            .prompt(reqVO.getPrompt())
                            .mode(reqVO.getMode())
                            .fileNames(reqVO.getFileNames())
                            .maskFileNames(reqVO.getMaskFileNames())
                            .fileName(reqVO.getFileName())
                            .fileDescription(reqVO.getFileDescription())
                            .model(reqVO.getModel())
                            .size(reqVO.getSize())
                            .quality(reqVO.getQuality())
                            .outputFormat(reqVO.getOutputFormat())
                            .n(reqVO.getN())
                            .build()
            );

            String terminalFreezeId = freezeId;
            freezeId = null;
            if (Boolean.TRUE.equals(result.getUsedFallback())) {
                memberQuotaBillingService.release(terminalFreezeId);
            } else {
                memberQuotaBillingService.settle(terminalFreezeId, surcharge);
            }

            WorkspaceImageGenerationRespVO respVO = WorkspaceImageGenerationRespVO.builder()
                    .data(result.getData())
                    .fileInfo(toFileRespList(result.getFileInfo()))
                    .requestId(result.getRequestId())
                    .mode(result.getMode())
                    .usedFallback(result.getUsedFallback())
                    .rawResponse(result.getRawResponse())
                    .build();
            return Response.<WorkspaceImageGenerationRespVO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(respVO)
                    .build();
        } catch (QuotaInsufficientException e) {
            memberQuotaBillingService.release(freezeId);
            return Response.<WorkspaceImageGenerationRespVO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(e.getMessage())
                    .build();
        } catch (IllegalArgumentException e) {
            memberQuotaBillingService.release(freezeId);
            return Response.<WorkspaceImageGenerationRespVO>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info(e.getMessage())
                    .build();
        } catch (Exception e) {
            memberQuotaBillingService.release(freezeId);
            log.error("生图工作台生成失败 errorType={}", e.getClass().getSimpleName());
            return Response.<WorkspaceImageGenerationRespVO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @GetMapping("/history")
    public Response<PageRespVO<WorkspaceImageHistoryBatchRespVO>> history(@RequestParam(name = "pageNo", defaultValue = "1") int pageNo,
                                                                          @RequestParam(name = "pageSize", defaultValue = "10") int pageSize) {
        try {
            Long ownerId = OwnerRequestContext.requireOwnerId();
            WorkspaceImageGenerationHistoryPage historyPage =
                    workspaceImageGenerationService.queryHistory(ownerId, pageNo, pageSize);

            List<WorkspaceImageHistoryBatchRespVO> list = historyPage.getList().stream()
                    .map(this::toHistoryRespVO)
                    .collect(Collectors.toList());
            return Response.<PageRespVO<WorkspaceImageHistoryBatchRespVO>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(PageRespVO.<WorkspaceImageHistoryBatchRespVO>builder()
                            .total(historyPage.getTotal())
                            .list(list)
                            .build())
                    .build();
        } catch (IllegalArgumentException e) {
            return Response.<PageRespVO<WorkspaceImageHistoryBatchRespVO>>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info(e.getMessage())
                    .build();
        } catch (Exception e) {
            log.error("查询生图历史失败 errorType={}", e.getClass().getSimpleName());
            return Response.<PageRespVO<WorkspaceImageHistoryBatchRespVO>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @DeleteMapping("/history/{requestId}")
    public Response<Boolean> deleteHistory(@PathVariable String requestId) {
        Long ownerId = OwnerRequestContext.requireOwnerId();
        boolean deleted = workspaceImageGenerationService.deleteHistory(ownerId, requestId);
        return Response.<Boolean>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(deleted ? ResponseCode.SUCCESS.getInfo() : "记录不存在或无权删除")
                .data(deleted)
                .build();
    }

    private WorkspaceImageHistoryBatchRespVO toHistoryRespVO(WorkspaceImageGenerationHistoryBatch batch) {
        return WorkspaceImageHistoryBatchRespVO.builder()
                .requestId(batch.getRequestId())
                .prompt(batch.getPrompt())
                .mode(batch.getMode())
                .size(batch.getSize())
                .batchCount(batch.getBatchCount())
                .sourceImageCount(batch.getSourceImageCount())
                .maskImageCount(batch.getMaskImageCount())
                .usedFallback(batch.getUsedFallback())
                .createdAt(batch.getCreatedAt())
                .images(toFileRespList(batch.getImages()))
                .build();
    }

    private List<WorkspaceImageFileRespVO> toFileRespList(List<WorkspaceImageFile> files) {
        if (files == null || files.isEmpty()) {
            return List.of();
        }
        return files.stream()
                .map(file -> WorkspaceImageFileRespVO.builder()
                        .fileName(file.getFileName())
                        .ossUrl(file.getOssUrl())
                        .domainUrl(file.getDomainUrl())
                        .downloadUrl(file.getDownloadUrl())
                        .previewUrl(file.getPreviewUrl())
                        .fileSize(file.getFileSize())
                        .mimeType(file.getMimeType())
                        .build())
                .collect(Collectors.toList());
    }
}
