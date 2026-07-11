package org.wwz.ai.trigger.http.agent;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.wwz.ai.api.response.Response;
import org.wwz.ai.application.agent.model.IModelCatalogQueryService;
import org.wwz.ai.domain.agent.model.valobj.AiClientModelVO;
import org.wwz.ai.trigger.http.agent.vo.ModelRespVO;
import org.wwz.ai.types.enums.ResponseCode;

import javax.annotation.Resource;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 用户侧可选模型目录接口。
 * 走网关 JWT（{@code /api/agent/**}），返回管理端配置且启用的模型清单，供聊天界面模型选择器使用。
 */
@RestController
@RequestMapping("/api/agent/models")
public class AgentModelController {

    @Resource
    private IModelCatalogQueryService modelCatalogQueryService;

    @GetMapping
    public Response<List<ModelRespVO>> list() {
        List<ModelRespVO> models = modelCatalogQueryService.listAvailableModels().stream()
                .map(this::toRespVO)
                .collect(Collectors.toList());

        return Response.<List<ModelRespVO>>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info("success")
                .data(models)
                .build();
    }

    private ModelRespVO toRespVO(AiClientModelVO modelVO) {
        return ModelRespVO.builder()
                .modelId(modelVO.getModelId())
                .modelName(modelVO.getModelName())
                .modelType(modelVO.getModelType())
                .build();
    }
}
