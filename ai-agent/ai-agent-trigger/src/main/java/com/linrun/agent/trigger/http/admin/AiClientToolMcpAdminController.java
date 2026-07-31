package com.linrun.agent.trigger.http.admin;

import com.linrun.agent.api.IAiClientToolMcpAdminService;
import com.linrun.agent.api.dto.AiClientToolMcpQueryRequestDTO;
import com.linrun.agent.api.dto.AiClientToolMcpRequestDTO;
import com.linrun.agent.api.dto.AiClientToolMcpResponseDTO;
import com.linrun.agent.api.response.Response;
import com.linrun.agent.types.common.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.linrun.agent.infrastructure.dao.IAiClientToolMcpDao;
import com.linrun.agent.infrastructure.dao.po.AiClientToolMcp;
import com.linrun.agent.domain.agent.runtime.tool.mcp.runtime.McpRegistry;
import com.linrun.agent.types.enums.ResponseCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * MCP客户端配置管理控制器
 * @description MCP客户端配置管理控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/ai-client-tool-mcp")
public class AiClientToolMcpAdminController implements IAiClientToolMcpAdminService {

    @Resource
    private IAiClientToolMcpDao aiClientToolMcpDao;

    @Resource
    private McpRegistry mcpRegistry;

    @Override
    @PostMapping("/create")
    public Response<Boolean> createAiClientToolMcp(@RequestBody AiClientToolMcpRequestDTO request) {
        try {
            log.info("创建MCP客户端配置请求");

            // DTO转PO
            AiClientToolMcp aiClientToolMcp = convertToAiClientToolMcp(request);
            aiClientToolMcp.setCreateTime(LocalDateTime.now());
            aiClientToolMcp.setUpdateTime(LocalDateTime.now());

            int result = aiClientToolMcpDao.insert(aiClientToolMcp);
            refreshRuntime(result);

            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(result > 0)
                    .build();
        } catch (Exception e) {
            log.error("创建MCP客户端配置失败", e);
            return Response.<Boolean>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(false)
                    .build();
        }
    }

    @Override
    @PutMapping("/update-by-id")
    public Response<Boolean> updateAiClientToolMcpById(@RequestBody AiClientToolMcpRequestDTO request) {
        try {
            log.info("根据ID更新MCP客户端配置请求");

            if (request.getId() == null) {
                return Response.<Boolean>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info("ID不能为空")
                        .data(false)
                        .build();
            }

            // DTO转PO
            AiClientToolMcp aiClientToolMcp = convertToAiClientToolMcp(request);
            aiClientToolMcp.setUpdateTime(LocalDateTime.now());

            int result = aiClientToolMcpDao.updateById(aiClientToolMcp);
            refreshRuntime(result);

            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(result > 0)
                    .build();
        } catch (Exception e) {
            log.error("根据ID更新MCP客户端配置失败", e);
            return Response.<Boolean>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(false)
                    .build();
        }
    }

    @Override
    @PutMapping("/update-by-mcp-id")
    public Response<Boolean> updateAiClientToolMcpByMcpId(@RequestBody AiClientToolMcpRequestDTO request) {
        try {
            log.info("根据MCP ID更新MCP客户端配置请求");

            if (!StringUtils.hasText(request.getMcpId())) {
                return Response.<Boolean>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info("MCP ID不能为空")
                        .data(false)
                        .build();
            }

            // DTO转PO
            AiClientToolMcp aiClientToolMcp = convertToAiClientToolMcp(request);
            aiClientToolMcp.setUpdateTime(LocalDateTime.now());

            int result = aiClientToolMcpDao.updateByMcpId(aiClientToolMcp);
            refreshRuntime(result);

            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(result > 0)
                    .build();
        } catch (Exception e) {
            log.error("根据MCP ID更新MCP客户端配置失败", e);
            return Response.<Boolean>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(false)
                    .build();
        }
    }

    @Override
    @DeleteMapping("/delete-by-id/{id}")
    public Response<Boolean> deleteAiClientToolMcpById(@PathVariable("id") Long id) {
        try {
            log.info("根据ID删除MCP客户端配置：{}", id);

            int result = aiClientToolMcpDao.deleteById(id);
            refreshRuntime(result);

            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(result > 0)
                    .build();
        } catch (Exception e) {
            log.error("根据ID删除MCP客户端配置失败", e);
            return Response.<Boolean>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(false)
                    .build();
        }
    }

    @Override
    @DeleteMapping("/delete-by-mcp-id/{mcpId}")
    public Response<Boolean> deleteAiClientToolMcpByMcpId(@PathVariable("mcpId") String mcpId) {
        try {
            log.info("根据MCP ID删除MCP客户端配置：{}", mcpId);

            int result = aiClientToolMcpDao.deleteByMcpId(mcpId);
            refreshRuntime(result);

            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(result > 0)
                    .build();
        } catch (Exception e) {
            log.error("根据MCP ID删除MCP客户端配置失败", e);
            return Response.<Boolean>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(false)
                    .build();
        }
    }

    @Override
    @GetMapping("/query-by-id/{id}")
    public Response<AiClientToolMcpResponseDTO> queryAiClientToolMcpById(@PathVariable("id") Long id) {
        try {
            log.info("根据ID查询MCP客户端配置：{}", id);

            AiClientToolMcp aiClientToolMcp = aiClientToolMcpDao.queryById(id);

            if (aiClientToolMcp == null) {
                return Response.<AiClientToolMcpResponseDTO>builder()
                        .code(ResponseCode.SUCCESS.getCode())
                        .info(ResponseCode.SUCCESS.getInfo())
                        .data(null)
                        .build();
            }

            AiClientToolMcpResponseDTO responseDTO = convertToAiClientToolMcpResponseDTO(aiClientToolMcp);

            return Response.<AiClientToolMcpResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTO)
                    .build();
        } catch (Exception e) {
            log.error("根据ID查询MCP客户端配置失败", e);
            return Response.<AiClientToolMcpResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(null)
                    .build();
        }
    }

    @Override
    @GetMapping("/query-by-mcp-id/{mcpId}")
    public Response<AiClientToolMcpResponseDTO> queryAiClientToolMcpByMcpId(@PathVariable("mcpId") String mcpId) {
        try {
            log.info("根据MCP ID查询MCP客户端配置：{}", mcpId);

            AiClientToolMcp aiClientToolMcp = aiClientToolMcpDao.queryByMcpId(mcpId);

            if (aiClientToolMcp == null) {
                return Response.<AiClientToolMcpResponseDTO>builder()
                        .code(ResponseCode.SUCCESS.getCode())
                        .info(ResponseCode.SUCCESS.getInfo())
                        .data(null)
                        .build();
            }

            AiClientToolMcpResponseDTO responseDTO = convertToAiClientToolMcpResponseDTO(aiClientToolMcp);

            return Response.<AiClientToolMcpResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTO)
                    .build();
        } catch (Exception e) {
            log.error("根据MCP ID查询MCP客户端配置失败", e);
            return Response.<AiClientToolMcpResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(null)
                    .build();
        }
    }

    @Override
    @GetMapping("/query-all")
    public Response<List<AiClientToolMcpResponseDTO>> queryAllAiClientToolMcps() {
        try {
            log.info("查询所有MCP客户端配置");

            List<AiClientToolMcp> aiClientToolMcps = aiClientToolMcpDao.queryAll();

            List<AiClientToolMcpResponseDTO> responseDTOs = aiClientToolMcps.stream()
                    .map(this::convertToAiClientToolMcpResponseDTO)
                    .collect(Collectors.toList());

            return Response.<List<AiClientToolMcpResponseDTO>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTOs)
                    .build();
        } catch (Exception e) {
            log.error("查询所有MCP客户端配置失败", e);
            return Response.<List<AiClientToolMcpResponseDTO>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(null)
                    .build();
        }
    }

    @Override
    @GetMapping("/query-by-status/{status}")
    public Response<List<AiClientToolMcpResponseDTO>> queryAiClientToolMcpsByStatus(@PathVariable("status") Integer status) {
        try {
            log.info("根据状态查询MCP客户端配置：{}", status);

            List<AiClientToolMcp> aiClientToolMcps = aiClientToolMcpDao.queryByStatus(status);

            List<AiClientToolMcpResponseDTO> responseDTOs = aiClientToolMcps.stream()
                    .map(this::convertToAiClientToolMcpResponseDTO)
                    .collect(Collectors.toList());

            return Response.<List<AiClientToolMcpResponseDTO>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTOs)
                    .build();
        } catch (Exception e) {
            log.error("根据状态查询MCP客户端配置失败", e);
            return Response.<List<AiClientToolMcpResponseDTO>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(null)
                    .build();
        }
    }

    @Override
    @GetMapping("/query-by-transport-type/{transportType}")
    public Response<List<AiClientToolMcpResponseDTO>> queryAiClientToolMcpsByTransportType(@PathVariable("transportType") String transportType) {
        try {
            log.info("根据传输类型查询MCP客户端配置：{}", transportType);

            List<AiClientToolMcp> aiClientToolMcps = aiClientToolMcpDao.queryByTransportType(transportType);

            List<AiClientToolMcpResponseDTO> responseDTOs = aiClientToolMcps.stream()
                    .map(this::convertToAiClientToolMcpResponseDTO)
                    .collect(Collectors.toList());

            return Response.<List<AiClientToolMcpResponseDTO>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTOs)
                    .build();
        } catch (Exception e) {
            log.error("根据传输类型查询MCP客户端配置失败", e);
            return Response.<List<AiClientToolMcpResponseDTO>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(null)
                    .build();
        }
    }

    @Override
    @GetMapping("/query-enabled")
    public Response<List<AiClientToolMcpResponseDTO>> queryEnabledAiClientToolMcps() {
        try {
            log.info("查询启用的MCP客户端配置");

            List<AiClientToolMcp> aiClientToolMcps = aiClientToolMcpDao.queryEnabledMcps();

            List<AiClientToolMcpResponseDTO> responseDTOs = aiClientToolMcps.stream()
                    .map(this::convertToAiClientToolMcpResponseDTO)
                    .collect(Collectors.toList());

            return Response.<List<AiClientToolMcpResponseDTO>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTOs)
                    .build();
        } catch (Exception e) {
            log.error("查询启用的MCP客户端配置失败", e);
            return Response.<List<AiClientToolMcpResponseDTO>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(null)
                    .build();
        }
    }

    @Override
    @PostMapping("/query-list")
    public Response<List<AiClientToolMcpResponseDTO>> queryAiClientToolMcpList(@RequestBody AiClientToolMcpQueryRequestDTO request) {
        try {
            log.info("根据查询条件查询MCP客户端配置列表");

            // 根据查询条件调用不同的DAO方法
            List<AiClientToolMcp> aiClientToolMcps;

            if (StringUtils.hasText(request.getMcpId())) {
                // 根据MCP ID查询
                AiClientToolMcp single = aiClientToolMcpDao.queryByMcpId(request.getMcpId());
                aiClientToolMcps = single != null ? List.of(single) : List.of();
            } else if (request.getStatus() != null) {
                // 根据状态查询
                aiClientToolMcps = aiClientToolMcpDao.queryByStatus(request.getStatus());
            } else if (StringUtils.hasText(request.getTransportType())) {
                // 根据传输类型查询
                aiClientToolMcps = aiClientToolMcpDao.queryByTransportType(request.getTransportType());
            } else {
                // 查询所有
                aiClientToolMcps = aiClientToolMcpDao.queryAll();
            }

            // 如果有MCP名称条件，进行过滤
            if (StringUtils.hasText(request.getMcpName())) {
                aiClientToolMcps = aiClientToolMcps.stream()
                        .filter(mcp -> mcp.getMcpName() != null &&
                                      mcp.getMcpName().contains(request.getMcpName()))
                        .collect(Collectors.toList());
            }

            List<AiClientToolMcpResponseDTO> responseDTOs = aiClientToolMcps.stream()
                    .map(this::convertToAiClientToolMcpResponseDTO)
                    .collect(Collectors.toList());

            return Response.<List<AiClientToolMcpResponseDTO>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTOs)
                    .build();
        } catch (Exception e) {
            log.error("根据查询条件查询MCP客户端配置列表失败", e);
            return Response.<List<AiClientToolMcpResponseDTO>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(null)
                    .build();
        }
    }

    /**
     * DTO转PO对象
     * @param requestDTO 请求DTO
     * @return PO对象
     */
    private AiClientToolMcp convertToAiClientToolMcp(AiClientToolMcpRequestDTO requestDTO) {
        AiClientToolMcp aiClientToolMcp = new AiClientToolMcp();
        BeanUtils.copyProperties(requestDTO, aiClientToolMcp);
        aiClientToolMcp.setProtocolVersion(defaultIfBlank(requestDTO.getProtocolVersion(), "2025-03-26"));
        aiClientToolMcp.setOauthScopesJson(toJsonArray(requestDTO.getOauthScopes()));
        aiClientToolMcp.setAllowedDomainsJson(toJsonArray(requestDTO.getAllowedDomains()));
        aiClientToolMcp.setToolAllowlistJson(toJsonArray(requestDTO.getToolAllowlist()));
        validateCredentialReference(requestDTO.getCredentialRef());
        aiClientToolMcp.setVersion(defaultIfBlank(requestDTO.getVersion(), "v1"));
        aiClientToolMcp.setConfigHash(defaultIfBlank(requestDTO.getConfigHash(),
                computeConfigHash(requestDTO)));
        return aiClientToolMcp;
    }

    /**
     * PO转响应DTO对象
     * @param aiClientToolMcp PO对象
     * @return 响应DTO
     */
    private AiClientToolMcpResponseDTO convertToAiClientToolMcpResponseDTO(AiClientToolMcp aiClientToolMcp) {
        AiClientToolMcpResponseDTO responseDTO = new AiClientToolMcpResponseDTO();
        BeanUtils.copyProperties(aiClientToolMcp, responseDTO);
        responseDTO.setOauthScopes(fromJsonArray(aiClientToolMcp.getOauthScopesJson()));
        responseDTO.setAllowedDomains(fromJsonArray(aiClientToolMcp.getAllowedDomainsJson()));
        responseDTO.setToolAllowlist(fromJsonArray(aiClientToolMcp.getToolAllowlistJson()));
        return responseDTO;
    }

    private String toJsonArray(List<String> values) {
        return JsonUtils.toJson(values == null ? List.of() : values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList());
    }

    private List<String> fromJsonArray(String rawJson) {
        if (!StringUtils.hasText(rawJson)) {
            return List.of();
        }
        try {
            List<String> values = JsonUtils.parseObject(rawJson, new TypeReference<List<String>>() { });
            return values == null ? List.of() : values;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private void validateCredentialReference(String credentialRef) {
        if (!StringUtils.hasText(credentialRef)) {
            return;
        }
        if (!credentialRef.matches("vault:[A-Za-z0-9._:/-]{1,200}")) {
            throw new IllegalArgumentException("credentialRef 必须是 vault: 前缀的密钥引用，不能提交凭据值");
        }
    }

    private String computeConfigHash(AiClientToolMcpRequestDTO request) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("mcpId", defaultIfBlank(request.getMcpId(), ""));
        payload.put("transportType", defaultIfBlank(request.getTransportType(), ""));
        payload.put("transportConfig", defaultIfBlank(request.getTransportConfig(), ""));
        payload.put("protocolVersion", defaultIfBlank(request.getProtocolVersion(), "2025-03-26"));
        payload.put("oauthAudience", defaultIfBlank(request.getOauthAudience(), ""));
        payload.put("oauthScopes", request.getOauthScopes() == null ? List.of() : request.getOauthScopes());
        payload.put("allowedDomains", request.getAllowedDomains() == null ? List.of() : request.getAllowedDomains());
        payload.put("toolAllowlist", request.getToolAllowlist() == null ? List.of() : request.getToolAllowlist());
        payload.put("credentialRef", defaultIfBlank(request.getCredentialRef(), ""));
        payload.put("version", defaultIfBlank(request.getVersion(), "v1"));
        payload.put("status", request.getStatus() == null ? 0 : request.getStatus());
        String canonical = JsonUtils.toJson(payload);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder("sha256:");
            for (byte value : digest) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (Exception error) {
            throw new IllegalStateException("MCP configuration hash unavailable", error);
        }
    }

    private String defaultIfBlank(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private void refreshRuntime(int changedRows) {
        if (changedRows > 0) {
            try {
                mcpRegistry.preloadAllEnabledMcps();
            } catch (RuntimeException e) {
                log.warn("MCP 配置已保存，但运行时刷新失败，将在下次加载时重试", e);
            }
        }
    }

}
