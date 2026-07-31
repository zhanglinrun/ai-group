package com.linrun.agent.eval.dataset;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatasetLoaderTest {
    @Test
    void loadsAllGoldenDatasetsWithStableCombinedHash() throws Exception {
        EvalDataset dataset = DatasetCatalog.loadDefault();

        assertEquals(17, dataset.cases().size());
        assertTrue(dataset.sha256().matches("sha256:[0-9a-f]{64}"));
        assertTrue(dataset.cases().stream().anyMatch(evalCase -> evalCase.id().equals("quota-one-settlement")));
    }

    @Test
    void rejectsDuplicateCaseIds() throws Exception {
        Path file = Files.createTempFile("eval-duplicate", ".jsonl");
        Files.writeString(file, "{\"id\":\"one\",\"input\":\"a\",\"mode\":\"STANDARD\"}\n"
                + "{\"id\":\"one\",\"input\":\"b\",\"mode\":\"STANDARD\"}\n");

        Exception error = assertThrows(Exception.class, () -> new DatasetLoader().load(file));

        assertTrue(error.getMessage().contains("duplicate eval case id"));
    }
}
