package com.example.fordagent.chat;

import java.util.List;

public record HistoryResponse(String conversationId, List<HistoryMessage> messages) {}
