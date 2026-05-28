package com.example.fordagent.chat;

import jakarta.servlet.http.HttpSession;
import java.util.UUID;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/chat")
public class ChatController {

    private static final String SESSION_ATTR = "conversationId";

    private final ChatClient chatClient;

    public ChatController(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @PostMapping
    public ChatResponse chat(@RequestBody ChatRequest request, HttpSession session) {
        String conversationId = resolveConversationId(request, session);

        String reply = chatClient
                .prompt()
                .user(request.message())
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .content();

        return new ChatResponse(conversationId, reply);
    }

    private static String resolveConversationId(ChatRequest request, HttpSession session) {
        if (request.conversationId() != null && !request.conversationId().isBlank()) {
            session.setAttribute(SESSION_ATTR, request.conversationId());
            return request.conversationId();
        }
        Object existing = session.getAttribute(SESSION_ATTR);
        if (existing instanceof String s && !s.isBlank()) {
            return s;
        }
        String fresh = UUID.randomUUID().toString();
        session.setAttribute(SESSION_ATTR, fresh);
        return fresh;
    }
}
