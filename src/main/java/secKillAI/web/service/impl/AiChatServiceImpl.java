package secKillAI.web.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import secKillAI.agent.AgentOrchestrator;
import secKillAI.agent.LegacyChatAgent;
import secKillAI.agent.event.SseEvents;
import secKillAI.agent.result.ReplyTemplates;
import secKillAI.web.service.IAiChatService;
import secKillAI.web.service.ISessionService;

/**
 * 对话入口。
 *
 * <p>两种模式，由 {@code ai.agent.mode} 切换：
 * <ul>
 *   <li>{@code orchestrated}（默认）：先识别意图，再按意图由代码分发（{@link AgentOrchestrator}）</li>
 *   <li>{@code legacy}：改造前的行为，全部交给模型自由调用工具（{@link LegacyChatAgent}）</li>
 * </ul>
 *
 * <p>输出统一包装成 SSE 事件流：{@code stage} → 若干 {@code delta} → {@code done}，
 * 出错时在流内发 {@code error} 再发 {@code done}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiChatServiceImpl implements IAiChatService {

    private static final String STAGE_ROUTING = "ROUTING";
    private static final String STAGE_REJECTED = "REJECTED";

    private final ISessionService sessionService;
    private final AgentOrchestrator agentOrchestrator;
    private final LegacyChatAgent legacyChatAgent;
    private final ObjectMapper objectMapper;

    @Value("${ai.agent.mode:orchestrated}")
    private String agentMode;

    @Override
    public Flux<ServerSentEvent<String>> chat(String msg, String token) {
        // 身份只从这里来：token → Redis → 手机号。模型全程接触不到 token。
        Long phone = sessionService.resolvePhone(token);
        if (phone == null) {
            return Flux.just(
                    SseEvents.stage(objectMapper, STAGE_REJECTED),
                    SseEvents.delta(objectMapper, ReplyTemplates.LOGIN_EXPIRED),
                    SseEvents.done(objectMapper));
        }

        Flux<String> text = plainChat(phone, msg);

        // 第一个事件先把阶段推出去，前端可以立刻显示"正在理解你的问题…"
        return Flux.concat(
                        Flux.just(SseEvents.stage(objectMapper, STAGE_ROUTING)),
                        text.map(chunk -> SseEvents.delta(objectMapper, chunk)),
                        Flux.just(SseEvents.done(objectMapper)))
                .onErrorResume(e -> {
                    // 到这里说明流已经开始推送了，HTTP 状态码改不了，
                    // 只能靠在流里发一条 error 事件告诉前端"不是没有回复，是出错了"
                    log.error("对话流式输出出错 userId={}，消息长度={}",
                            phone, msg == null ? 0 : msg.length(), e);
                    return Flux.just(
                            SseEvents.error(objectMapper, "系统有点忙，请稍后再试"),
                            SseEvents.done(objectMapper));
                });
    }

    /** 按模式拿到纯文本流。异常不在这里吞，交给上层统一转成 error 事件。 */
    private Flux<String> plainChat(Long phone, String msg) {
        if ("legacy".equalsIgnoreCase(agentMode)) {
            log.debug("agent 模式=legacy，userId={}", phone);
            // 人工切到 legacy 是"出事时的保险丝"，保留改造前的完整工具（含下单/退款）。
            // 注意：编排模式内部的降级是 readonly=true，两者刻意不同。
            return legacyChatAgent.chat(phone, msg, false);
        }
        return agentOrchestrator.handle(phone, msg);
    }
}
