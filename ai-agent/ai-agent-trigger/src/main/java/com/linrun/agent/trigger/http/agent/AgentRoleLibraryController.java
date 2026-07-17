package com.linrun.agent.trigger.http.agent;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.linrun.agent.api.response.Response;
import com.linrun.agent.domain.agent.role.IFixRoleService;
import com.linrun.agent.domain.agent.model.valobj.FixRoleVO;
import com.linrun.agent.trigger.http.agent.vo.FixRoleRespVO;
import com.linrun.agent.types.enums.ResponseCode;

import javax.annotation.Resource;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Fix 角色库接口
 */
@RestController
@RequestMapping("/api/agent/role-library")
public class AgentRoleLibraryController {

    @Resource
    private IFixRoleService fixRoleService;

    @GetMapping("/list")
    public Response<List<FixRoleRespVO>> list() {
        List<FixRoleRespVO> roles = fixRoleService.queryAvailableRoles().stream()
                .map(this::toRespVO)
                .collect(Collectors.toList());

        return Response.<List<FixRoleRespVO>>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info("success")
                .data(roles)
                .build();
    }

    private FixRoleRespVO toRespVO(FixRoleVO roleVO) {
        return FixRoleRespVO.builder()
                .agentId(roleVO.getAgentId())
                .agentName(roleVO.getAgentName())
                .description(roleVO.getDescription())
                .defaultRole(roleVO.isDefaultRole())
                .build();
    }
}
