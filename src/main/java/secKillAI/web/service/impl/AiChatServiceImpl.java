package secKillAI.web.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import secKillAI.agent.AgentOrchestrator;
import secKillAI.agent.LegacyChatAgent;
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
 * <p>留这个开关的意义：新链路万一出问题，改一行配置就能退回改造前的行为，不需要回滚代码。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiChatServiceImpl implements IAiChatService {

    private static final String LOGIN_EXPIRED = "登录状态已失效，请重新登录后再试。";

    private final ISessionService sessionService;
    private final AgentOrchestrator agentOrchestrator;
    private final LegacyChatAgent legacyChatAgent;

    @Value("${ai.agent.mode:orchestrated}")
    private String agentMode;

    @Override
    public Flux<String> chat(String msg, String token) {
        // 身份只从这里来：token → Redis → 手机号。模型全程接触不到 token。
        Long phone = sessionService.resolvePhone(token);
        if (phone == null) {
            return Flux.just(LOGIN_EXPIRED);
        }

        if ("legacy".equalsIgnoreCase(agentMode)) {
            log.debug("agent 模式=legacy，userId={}", phone);
            // 降级链路同样需要兜底：它是在"新链路出问题"时才会被启用，
            // 恰恰是最需要保证用户不会看到半截回复的时候。
            return legacyChatAgent.chat(phone, msg)
                    .onErrorResume(e -> {
                        log.error("legacy 链路出错，userId={}，消息长度={}",
                                phone, msg == null ? 0 : msg.length(), e);
                        log.debug("出错的消息原文：{}", msg);
                        return Flux.just(AgentOrchestrator.FALLBACK_REPLY);
                    });
        }

        return agentOrchestrator.handle(phone, msg);
    }
}
