package com.linrun.agent.trigger.http.agent;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.linrun.agent.api.response.Response;
import com.linrun.agent.domain.agent.adapter.port.ModelCatalogPort;
import com.linrun.agent.domain.agent.model.valobj.AiClientModelVO;
import com.linrun.agent.trigger.http.agent.vo.ModelRespVO;
import com.linrun.agent.types.enums.ResponseCode;

import jakarta.annotation.Resource;
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
    private ModelCatalogPort modelCatalogPort;

    @GetMapping
    public Response<List<ModelRespVO>> list() {
        List<ModelRespVO> models = modelCatalogPort.listAvailableModels().stream()
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
