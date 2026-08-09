package com.aigroup.groupbuy.infrastructure.adapter.repository;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TradeIdentifierTest {

    @Test
    public void identifiersAreCompactUuidValuesWithNoCollisionsInBatch() {
        Set<String> identifiers = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            String identifier = TradeRepository.newIdentifier();
            assertEquals(32, identifier.length());
            assertTrue(identifier.matches("[0-9a-f]{32}"));
            identifiers.add(identifier);
        }
        assertEquals(10_000, identifiers.size());
    }
}
