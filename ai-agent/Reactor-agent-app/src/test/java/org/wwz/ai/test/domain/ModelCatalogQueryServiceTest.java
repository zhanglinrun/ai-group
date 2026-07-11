package org.wwz.ai.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.wwz.ai.application.agent.model.ModelCatalogQueryService;
import org.wwz.ai.domain.agent.adapter.port.ModelCatalogPort;
import org.wwz.ai.domain.agent.model.valobj.AiClientModelVO;

import java.util.List;

public class ModelCatalogQueryServiceTest {

    @Test
    public void shouldDelegateCatalogOperationsToPort() {
        ModelCatalogPort port = Mockito.mock(ModelCatalogPort.class);
        ModelCatalogQueryService service = new ModelCatalogQueryService(port);
        List<AiClientModelVO> models = List.of(AiClientModelVO.builder()
                .modelId("model-1")
                .modelName("qwen-plus")
                .modelType("openai")
                .build());
        Mockito.when(port.listAvailableModels()).thenReturn(models);
        Mockito.when(port.isModelAvailable("model-1")).thenReturn(true);

        Assert.assertSame(models, service.listAvailableModels());
        Assert.assertTrue(service.isModelAvailable("model-1"));
        service.invalidateCatalog();

        Mockito.verify(port).listAvailableModels();
        Mockito.verify(port).isModelAvailable("model-1");
        Mockito.verify(port).invalidate();
    }
}
