package secKillAI.config;

import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import secKillAI.mongo.store.MemoryStore;

@Configuration
public class SecKillAgentConfig {
    @Autowired
    private MemoryStore memoryStore;
    @Value("${chat.memoryMax}")
    private int memoryMax;
    @Bean
    public ChatMemoryProvider SecKillChatMemoryProvider(){
        return memoryId -> MessageWindowChatMemory.builder().id(memoryId)
                .chatMemoryStore(memoryStore)
                .maxMessages(memoryMax)
                .build();
    }
}
