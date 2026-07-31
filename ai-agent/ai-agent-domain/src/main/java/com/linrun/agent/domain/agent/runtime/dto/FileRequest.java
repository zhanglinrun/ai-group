package com.linrun.agent.domain.agent.runtime.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileRequest {
    private String requestId;

    /**
     * The tool schema uses camel-case {@code fileName}. Accept the historic
     * {@code filename} spelling as an input alias so replayed or older model
     * calls do not silently lose the requested artifact name.
     */
    @JsonAlias("filename")
    private String fileName;
    private String description;
    private String content;
}
