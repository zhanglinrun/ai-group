package com.linrun.agent.trigger.http.agent;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import com.linrun.agent.api.response.Response;
import com.linrun.agent.domain.agent.runtime.tool.skill.UserSkillDefinition;
import com.linrun.agent.domain.agent.runtime.tool.skill.UserSkillExtensionService;
import com.linrun.agent.types.agent.owner.OwnerRequestContext;
import com.linrun.agent.types.enums.ResponseCode;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/agent/extensions/skills")
@RequiredArgsConstructor
public class UserSkillController {

    private final UserSkillExtensionService userSkillExtensionService;

    @GetMapping
    public Response<List<UserSkillDefinition>> list() {
        return success(userSkillExtensionService.list(ownerId()));
    }

    @GetMapping("/{skillName}")
    public Response<UserSkillDefinition> detail(@PathVariable String skillName) {
        return execute(() -> userSkillExtensionService.getRequired(ownerId(), skillName));
    }

    @PostMapping
    public Response<UserSkillDefinition> upload(@RequestPart("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return failure("请选择 Skill zip 文件");
        }
        try {
            return success(userSkillExtensionService.install(ownerId(), file.getInputStream()));
        } catch (IOException e) {
            return failure("读取 Skill zip 文件失败");
        } catch (RuntimeException e) {
            return failure(e.getMessage());
        }
    }

    @PutMapping("/{skillName}/enabled")
    public Response<UserSkillDefinition> setEnabled(@PathVariable String skillName,
                                                    @RequestParam boolean enabled) {
        return execute(() -> userSkillExtensionService.setEnabled(ownerId(), skillName, enabled));
    }

    @DeleteMapping("/{skillName}")
    public Response<Boolean> delete(@PathVariable String skillName) {
        try {
            userSkillExtensionService.delete(ownerId(), skillName);
            return success(Boolean.TRUE);
        } catch (RuntimeException e) {
            return failure(e.getMessage());
        }
    }

    private String ownerId() {
        return OwnerRequestContext.requireOwnerIdAsString();
    }

    private <T> Response<T> execute(java.util.function.Supplier<T> action) {
        try {
            return success(action.get());
        } catch (RuntimeException e) {
            return failure(e.getMessage());
        }
    }

    private <T> Response<T> success(T data) {
        return Response.<T>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(data)
                .build();
    }

    private <T> Response<T> failure(String message) {
        return Response.<T>builder()
                .code(ResponseCode.UN_ERROR.getCode())
                .info(message)
                .build();
    }
}
