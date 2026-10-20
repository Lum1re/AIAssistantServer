package secKillAI.agent;

import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.service.AiServices;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import secKillAI.assistant.SecKillAssistant;
import secKillAI.tools.OrderTool;
import secKillAI.tools.QueryTool;
import secKillAI.tools.RefundTool;
import secKillAI.tools.SeckillOrderTool;
import secKillAI.web.mapper.OrderInfoMapper;
import secKillAI.web.service.IOrderOperateService;

/**
 * 原来的「全工具自由对话」链路，原样保留。
 *
 * <p>它的定位变了：从主链路变成**降级链路**。意图识别不出来（{@link Intent#UNKNOWN}）、
 * 或者某个意图还没有编排实现时，走这里，保证客服功能不会因为改造而退化。
 *
 * <p>相比改造前只加了一处：{@code maxSequentialToolsInvocations(8)}。
 * 不加的话模型理论上可以在工具之间来回跳到默认上限，一次对话烧掉大量 token。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LegacyChatAgent implements FallbackChat {

    /** 一次对话里最多允许连续调 8 次工具，超过就抛错让上层兜底 */
    private static final int MAX_TOOL_INVOCATIONS = 8;

    private final StreamingChatLanguageModel streamingChatLanguageModel;
    private final ChatMemoryProvider chatMemoryProvider;
    private final QueryTool queryTool;
    private final OrderInfoMapper orderInfoMapper;
    private final IOrderOperateService orderOperateService;

    /**
     * @param userId 手机号，作为会话记忆的 memoryId
     */
    @Override
    public Flux<String> chat(Long userId, String msg) {
        SecKillAssistant assistant = AiServices.builder(SecKillAssistant.class)
                .chatMemoryProvider(chatMemoryProvider)
                .streamingChatLanguageModel(streamingChatLanguageModel)
                .maxSequentialToolsInvocations(MAX_TOOL_INVOCATIONS)
                .tools(queryTool,
                        new OrderTool(orderInfoMapper, userId),
                        new SeckillOrderTool(orderOperateService, userId),
                        new RefundTool(orderOperateService, userId))
                .build();

        return assistant.chat(userId, msg);
    }
}
