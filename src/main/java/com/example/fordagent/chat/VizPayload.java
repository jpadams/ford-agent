package com.example.fordagent.chat;

import java.util.List;

public record VizPayload(List<VizNode> nodes, List<VizRelationship> relationships) {

    public static VizPayload empty() {
        return new VizPayload(List.of(), List.of());
    }
}
