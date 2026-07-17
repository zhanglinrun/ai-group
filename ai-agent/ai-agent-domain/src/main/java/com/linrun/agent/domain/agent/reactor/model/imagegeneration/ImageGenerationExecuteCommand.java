package com.linrun.agent.domain.agent.reactor.model.imagegeneration;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 统一的生图执行命令。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImageGenerationExecuteCommand {

    private String requestId;

    private String prompt;

    private String mode;

    private List<String> fileNames;

    private List<String> maskFileNames;

    private String fileName;

    private String fileDescription;

    private String model;

    private String size;

    private String quality;

    private String outputFormat;

    private Integer n;

    private Integer timeoutSeconds;
}
