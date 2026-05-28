package com.example.fordagent.chat;

import java.util.List;
import java.util.Map;

public record VizNode(String id, List<String> labels, String caption, Map<String, Object> properties) {}
