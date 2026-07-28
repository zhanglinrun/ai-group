package com.linrun.agent.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import com.linrun.agent.domain.agent.rag.DataAgentQueryService;
import com.linrun.agent.domain.agent.reactor.data.QueryResult;
import com.linrun.agent.domain.agent.reactor.data.dto.ColumnEsRecallReq;
import com.linrun.agent.domain.agent.reactor.data.dto.ColumnVectorRecallReq;
import com.linrun.agent.trigger.http.AiAgentController;
import com.linrun.agent.trigger.http.admin.AiClientRagOrderAdminController;
import com.linrun.agent.trigger.http.agent.AgentRoleLibraryController;
import com.linrun.agent.trigger.http.dataagent.DataAgentController;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 锁定统一 Agent 入口 / DataAgent HTTP 路由和代表性委派行为。
 */
public class ReactorHttpControllerTest {

    @Test
    public void shouldExposeAgentAndDataRouteSetFromTriggerControllers() {
        Assert.assertTrue(DataAgentController.class.getPackageName().startsWith("com.linrun.agent.trigger.http"));

        Set<String> routes = new LinkedHashSet<>(extractRoutes(DataAgentController.class));

        Assert.assertEquals(Set.of(
                "POST /data/vectorRecall",
                "POST /data/esRecall",
                "GET /data/allModels",
                "GET /data/previewData"
        ), routes);

        Set<String> agentRoutes = extractRoutes(AiAgentController.class);
        Assert.assertTrue(agentRoutes.contains("POST /web/api/v1/gpt/queryAgentStreamIncr"));
        Assert.assertFalse(agentRoutes.stream().anyMatch(route -> route.endsWith("/AutoAgent")));
    }

    @Test
    public void shouldInjectBoundaryOwnedServicesIntoAgentEntryControllers() {
        List<String> aiAgentFieldTypes = Arrays.stream(AiAgentController.class.getDeclaredFields())
                .map(field -> field.getType().getName())
                .collect(Collectors.toList());
        List<String> dataAgentFieldTypes = Arrays.stream(DataAgentController.class.getDeclaredFields())
                .map(field -> field.getType().getName())
                .collect(Collectors.toList());
        List<String> roleLibraryFieldTypes = Arrays.stream(AgentRoleLibraryController.class.getDeclaredFields())
                .map(field -> field.getType().getName())
                .collect(Collectors.toList());
        List<String> ragAdminFieldTypes = Arrays.stream(AiClientRagOrderAdminController.class.getDeclaredFields())
                .map(field -> field.getType().getName())
                .collect(Collectors.toList());

        Assert.assertTrue(aiAgentFieldTypes.contains("com.linrun.agent.domain.agent.service.armory.IArmoryService"));
        Assert.assertTrue(aiAgentFieldTypes.contains("com.linrun.agent.trigger.service.GptQueryIngressService"));
        Assert.assertFalse(aiAgentFieldTypes.contains("com.linrun.agent.domain.agent.service.dispatch.IAgentDispatchService"));
        Assert.assertFalse(aiAgentFieldTypes.contains("com.linrun.agent.domain.agent.reactor.service.IGptProcessService"));

        Assert.assertTrue(dataAgentFieldTypes.contains("com.linrun.agent.domain.agent.rag.DataAgentQueryService"));
        Assert.assertFalse(dataAgentFieldTypes.contains("com.linrun.agent.domain.agent.reactor.service.DataAgentService"));
        Assert.assertFalse(dataAgentFieldTypes.contains("com.linrun.agent.domain.agent.rag.SchemaRecallService"));
        Assert.assertFalse(dataAgentFieldTypes.contains("com.linrun.agent.domain.agent.reactor.service.ChatModelInfoService"));

        Assert.assertTrue(roleLibraryFieldTypes.contains("com.linrun.agent.domain.agent.role.IFixRoleService"));

        Assert.assertFalse(ragAdminFieldTypes.contains("com.linrun.agent.domain.agent.rag.IRagService"));
    }

    @Test
    public void shouldKeepRepresentativeDelegationAndResponseShapes() throws Exception {
        DataAgentController dataAgentController = new DataAgentController();
        DataAgentQueryService dataAgentQueryService = Mockito.mock(DataAgentQueryService.class);
        ReflectionTestUtils.setField(dataAgentController, "dataAgentQueryService", dataAgentQueryService);

        ColumnVectorRecallReq vectorRecallReq = new ColumnVectorRecallReq();
        List<Map<String, Object>> vectorResult = List.of(Map.of("column", "user_name"));
        Mockito.when(dataAgentQueryService.vectorRecall(vectorRecallReq)).thenReturn(vectorResult);
        Assert.assertSame(vectorResult, dataAgentController.vectorRecall(vectorRecallReq));

        ColumnEsRecallReq esRecallReq = new ColumnEsRecallReq();
        List<Map<String, Object>> esResult = List.of(Map.of("value", "杭州"));
        Mockito.when(dataAgentQueryService.esRecall(esRecallReq)).thenReturn(esResult);
        Assert.assertSame(esResult, dataAgentController.esRecall(esRecallReq));

        List<String> modelList = List.of("sales_model");
        Mockito.when(dataAgentQueryService.queryAllModelsWithSchema()).thenReturn((List) modelList);
        Map<String, Object> allModels = dataAgentController.allModels();
        Assert.assertEquals(200, allModels.get("code"));
        Assert.assertSame(modelList, allModels.get("data"));

        QueryResult previewRows = new QueryResult();
        previewRows.setDataList(List.of(Map.of("gmv", 123)));
        Mockito.when(dataAgentQueryService.previewData("sales_model")).thenReturn(previewRows);
        Map<String, Object> preview = dataAgentController.previewData("sales_model");
        Assert.assertEquals(200, preview.get("code"));
        Assert.assertSame(previewRows, preview.get("data"));
    }

    private Set<String> extractRoutes(Class<?> controllerClass) {
        String prefix = resolveFirstPath(controllerClass.getAnnotation(RequestMapping.class));
        Set<String> routes = new LinkedHashSet<>();
        for (Method method : controllerClass.getDeclaredMethods()) {
            PostMapping postMapping = method.getAnnotation(PostMapping.class);
            if (postMapping != null) {
                routes.add("POST " + normalizePath(prefix, resolveFirstPath(postMapping.value(), postMapping.path())));
                continue;
            }
            GetMapping getMapping = method.getAnnotation(GetMapping.class);
            if (getMapping != null) {
                routes.add("GET " + normalizePath(prefix, resolveFirstPath(getMapping.value(), getMapping.path())));
                continue;
            }
            RequestMapping requestMapping = method.getAnnotation(RequestMapping.class);
            if (requestMapping != null) {
                routes.add("REQUEST " + normalizePath(prefix, resolveFirstPath(requestMapping.value(), requestMapping.path())));
            }
        }
        return routes;
    }

    private String resolveFirstPath(RequestMapping requestMapping) {
        if (requestMapping == null) {
            return "";
        }
        return resolveFirstPath(requestMapping.value(), requestMapping.path());
    }

    private String resolveFirstPath(String[] value, String[] path) {
        if (value != null && value.length > 0) {
            return value[0];
        }
        if (path != null && path.length > 0) {
            return path[0];
        }
        return "";
    }

    private String normalizePath(String prefix, String path) {
        String normalizedPrefix = trimSlash(prefix == null ? "" : prefix.trim());
        String normalizedPath = trimSlash(path == null ? "" : path.trim());
        String merged;
        if (normalizedPrefix.isEmpty()) {
            merged = normalizedPath;
        } else if (normalizedPath.isEmpty()) {
            merged = normalizedPrefix;
        } else {
            merged = normalizedPrefix + "/" + normalizedPath;
        }
        return "/" + merged.replaceAll("/{2,}", "/");
    }

    private String trimSlash(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value;
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
