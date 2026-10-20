package secKillAI.assistant;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import reactor.core.publisher.Flux;

/**
 * AI 客服接口。
 *
 * 这里没有用 @AiService 注解，而是在 AiChatServiceImpl 里用 AiServices.builder()
 * 每次请求现搭一个助手——因为 OrderTool / RefundTool 需要绑定当前用户的身份。
 */
public interface SecKillAssistant {
    @SystemMessage(fromResource = "prompts/seckill-prompt.txt")
    Flux<String> chat(@MemoryId Long memoryId, @UserMessage String msg);
}
