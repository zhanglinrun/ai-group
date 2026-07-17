package com.linrun.agent.trigger.http.admin;

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
import com.linrun.agent.domain.agent.runtime.tool.skill.SystemSkillAdminService;
import com.linrun.agent.domain.agent.runtime.tool.skill.UserSkillDefinition;
import com.linrun.agent.types.enums.ResponseCode;

import java.util.List;
import java.util.function.Supplier;

@RestController
@RequestMapping("/api/v1/admin/system-skills")
@RequiredArgsConstructor
public class SystemSkillAdminController {

    private final SystemSkillAdminService service;

    @GetMapping
    public Response<List<UserSkillDefinition>> list() {
        return execute(service::list);
    }

    @GetMapping("/{name}")
    public Response<UserSkillDefinition> detail(@PathVariable String name) {
        return execute(() -> service.getRequired(name));
    }

    @PostMapping
    public Response<UserSkillDefinition> upload(@RequestPart("file") MultipartFile file) {
        return execute(() -> {
            try {
                return service.install(file.getInputStream());
            } catch (java.io.IOException e) {
                throw new IllegalArgumentException("读取 Skill zip 文件失败", e);
            }
        });
    }

    @PutMapping("/{name}/enabled")
    public Response<UserSkillDefinition> setEnabled(@PathVariable String name,
                                                    @RequestParam boolean enabled) {
        return execute(() -> service.setEnabled(name, enabled));
    }

    @DeleteMapping("/{name}")
    public Response<Boolean> delete(@PathVariable String name) {
        return execute(() -> {
            service.delete(name);
            return Boolean.TRUE;
        });
    }

    private <T> Response<T> execute(Supplier<T> action) {
        try {
            return Response.<T>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(action.get())
                    .build();
        } catch (RuntimeException e) {
            return Response.<T>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(e.getMessage())
                    .build();
        }
    }
}
