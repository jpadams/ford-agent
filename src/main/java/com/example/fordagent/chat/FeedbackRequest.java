package com.example.fordagent.chat;

public record FeedbackRequest(
        String conversationId, String rating, Integer messageIndex, String messagePreview) {}
