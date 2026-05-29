package com.example.fordagent.chat;

import com.example.fordagent.tools.VizCollector;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
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
        long start = System.nanoTime();
        log.info("chat user conversationId={} message={}", conversationId, oneLine(request.message()));

        String reply = chatClient
                .prompt()
                .user(request.message())
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .content();

        VizPayload viz = vizCollector.snapshot();
        log.info("chat assistant conversationId={} replyChars={} vizNodes={} vizRels={} elapsedMs={}",
                conversationId, reply == null ? 0 : reply.length(),
                viz.nodes().size(), viz.relationships().size(),
                (System.nanoTime() - start) / 1_000_000);
        return new ChatResponse(conversationId, reply, viz);
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

    @PostMapping("/feedback")
    public void feedback(@RequestBody FeedbackRequest request, HttpSession session) {
        String conversationId = (request != null
                        && request.conversationId() != null
                        && !request.conversationId().isBlank())
                ? request.conversationId()
                : (String) session.getAttribute(SESSION_ATTR);
        String rating = request == null ? null : request.rating();
        Integer idx = request == null ? null : request.messageIndex();
        String preview = request == null ? "" : oneLine(request.messagePreview());
        log.info(
                "chat feedback conversationId={} rating={} messageIndex={} preview={}",
                conversationId, rating, idx, preview);
    }

    @PostMapping("/new")
    public HistoryResponse newConversation(HttpSession session) {
        Object existing = session.getAttribute(SESSION_ATTR);
        if (existing instanceof String s && !s.isBlank()) {
            chatMemory.clear(s);
            log.info("chat newConversation cleared previousConversationId={}", s);
        }
        String fresh = UUID.randomUUID().toString();
        session.setAttribute(SESSION_ATTR, fresh);
        log.info("chat newConversation conversationId={}", fresh);
        return new HistoryResponse(fresh, List.of());
    }

    private static String oneLine(String s) {
        if (s == null) return "";
        String flat = s.replaceAll("\\s+", " ").trim();
        return flat.length() > 500 ? flat.substring(0, 500) + "…" : flat;
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
