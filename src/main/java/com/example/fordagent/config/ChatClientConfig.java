package com.example.fordagent.config;

import com.example.fordagent.tools.Neo4jTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatClientConfig {

    private static final String SYSTEM_PROMPT = """
            You are an assistant that can answer questions about data stored in a Neo4j graph database.
            You have two tools available:
              - getSchema: inspect labels, relationship types, and properties in the graph.
              - runReadQuery: execute a read-only Cypher query and return rows.
            Prefer calling getSchema first when you don't know the data model. Always author Cypher yourself
            based on the schema. Read-only queries only; the tool will refuse writes.
            """;

    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(20)
                .build();
    }

    @Bean
    public ChatClient chatClient(ChatModel chatModel, ChatMemory chatMemory, Neo4jTools neo4jTools) {
        return ChatClient.builder(chatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .defaultTools(neo4jTools)
                .build();
    }
}
