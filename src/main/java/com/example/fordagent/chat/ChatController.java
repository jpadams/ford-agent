package com.example.fordagent.chat;

import com.example.fordagent.tools.VizCollector;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.UUID;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/chat")
public class ChatController {

    private static final String SESSION_ATTR = "conversationId";

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final VizCollector vizCollector;

    public ChatController(ChatClient chatClient, ChatMemory chatMemory, VizCollector vizCollector) {
        this.chatClient = chatClient;
        this.chatMemory = chatMemory;
        this.vizCollector = vizCollector;
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

        return new ChatResponse(conversationId, reply, vizCollector.snapshot());
    }

    @GetMapping
    public HistoryResponse history(HttpSession session) {
        String conversationId = resolveConversationId(null, session);
        List<Message> messages = chatMemory.get(conversationId);
        List<HistoryMessage> view = messages.stream()
                .filter(m -> m.getMessageType() == MessageType.USER
                        || m.getMessageType() == MessageType.ASSISTANT)
                .map(m -> new HistoryMessage(m.getMessageType().getValue(), m.getText()))
                .toList();
        return new HistoryResponse(conversationId, view);
    }

    @PostMapping("/new")
    public HistoryResponse newConversation(HttpSession session) {
        Object existing = session.getAttribute(SESSION_ATTR);
        if (existing instanceof String s && !s.isBlank()) {
            chatMemory.clear(s);
        }
        String fresh = UUID.randomUUID().toString();
        session.setAttribute(SESSION_ATTR, fresh);
        return new HistoryResponse(fresh, List.of());
    }

    private static String resolveConversationId(ChatRequest request, HttpSession session) {
        if (request != null && request.conversationId() != null && !request.conversationId().isBlank()) {
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
