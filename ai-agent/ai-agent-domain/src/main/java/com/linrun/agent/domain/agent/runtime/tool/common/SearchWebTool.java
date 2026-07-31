package com.linrun.agent.domain.agent.runtime.tool.common;

/**
 * Canonical P60 name for the built-in web search capability.
 *
 * <p>The legacy {@code deep_search} name remains available for compatibility;
 * this adapter exposes the stable registry name without duplicating the
 * network execution implementation.</p>
 */
public class SearchWebTool extends DeepSearchTool {

    @Override
    public String getName() {
        return "search_web";
    }

}
