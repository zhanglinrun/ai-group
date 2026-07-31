package com.linrun.agent.eval;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvalCliTest {
    @Test
    void runsOfflineSuiteAndWritesAllRequiredReports() throws Exception {
        Path output = Files.createTempDirectory("researchpilot-eval-report");

        int code = EvalCli.run(new String[]{"--output", output.toString()}, Map.of(), Path.of(".").toAbsolutePath().normalize());

        assertEquals(0, code);
        assertTrue(Files.exists(output.resolve("result.json")));
        assertTrue(Files.exists(output.resolve("report.md")));
        assertTrue(Files.exists(output.resolve("report.html")));
        assertTrue(Files.exists(output.resolve("regression-set.jsonl")));
        assertEquals("", Files.readString(output.resolve("regression-set.jsonl")));
    }
}
