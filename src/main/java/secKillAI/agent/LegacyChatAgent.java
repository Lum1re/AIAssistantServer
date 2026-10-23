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
     * @param userId   手机号，作为会话记忆的 memoryId
     * @param readonly {@code true} 时<b>只挂只读工具</b>（查商品/库存/详情/订单），
     *                 不挂下单和退款。
     *
     *                 <p>为什么必须这么分：
     *                 <ul>
     *                   <li><b>编排模式的降级</b>（readonly=true）：意图识别失败才走这里。
     *                       如果此时还挂着写工具，模型又能自己判断"用户是不是确认了"，
     *                       票据那一整套就白做了。<b>只要有一条路能绕过票据，票据就等于不存在。</b></li>
     *                   <li><b>人工切到 legacy 模式</b>（readonly=false）：这是出事时的保险丝，
     *                       要保留改造前的完整能力，否则回退等于降级服务。</li>
     *                 </ul>
     */
    @Override
    public Flux<String> chat(Long userId, String msg, boolean readonly) {
        AiServices<SecKillAssistant> services = AiServices.builder(SecKillAssistant.class)
                .chatMemoryProvider(chatMemoryProvider)
                .streamingChatLanguageModel(streamingChatLanguageModel)
                .maxSequentialToolsInvocations(MAX_TOOL_INVOCATIONS);

        if (readonly) {
            log.info("降级链路以只读方式运行 userId={}（不提供下单/退款工具）", userId);
            services = services.tools(queryTool, new OrderTool(orderInfoMapper, userId));
        } else {
            log.debug("legacy 模式以完整工具运行 userId={}", userId);
            services = services.tools(queryTool,
                    new OrderTool(orderInfoMapper, userId),
                    new SeckillOrderTool(orderOperateService, userId),
                    new RefundTool(orderOperateService, userId));
        }

        return services.build().chat(userId, msg);
    }
}
