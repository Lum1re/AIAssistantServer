package secKillAI.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import secKillAI.agent.handler.HandlerOutcome;
import secKillAI.agent.handler.IntentHandler;
import secKillAI.agent.state.AgentState;
import secKillAI.agent.state.AgentStateStore;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 编排器：把「模型判断意图」变成「代码决定执行」，并负责记住进行到哪一步。
 *
 * <p>一轮请求的处理顺序：
 * <pre>
 *   ① 读会话状态
 *   ② 有待办且用户说"算了" → 清状态，结束
 *   ③ 正在等参数：
 *        - 模型识别成同一个意图 → 合并参数继续
 *        - 模型识别不出（裸订单号这种）→ 当作补参数，补上再验证
 *        - 补了还是不行 / 识别成别的事 → 放弃旧任务
 *   ④ 正常流程：识别意图 → 查表分发
 *   ⑤ 处理器追问 → 保存 stage=ASKING；否则清空状态
 * </pre>
 *
 * <p>阻塞说明：意图识别 + 数据库查询都是阻塞调用，整段放在
 * {@link Schedulers#boundedElastic()} 上执行，避免占住请求线程。
 */
@Slf4j
@Component
public class AgentOrchestrator {

    /** 内部出错时给用户的兜底话术。刻意不暴露任何技术细节。 */
    public static final String FALLBACK_REPLY = "抱歉，处理你的问题时出了点意外，请稍后再试，或者转人工客服。";

    private static final String GIVE_UP_REPLY = "好的，已取消。有别的需要随时说。";

    /** 待办状态下，用户说这些词就视为放弃（带长度限制，避免长句被误判） */
    private static final List<String> GIVE_UP_WORDS =
            List.of("算了", "取消", "不查了", "不用了", "不要了", "先不", "不了", "停");

    private final IntentClassifier intentClassifier;
    private final FallbackChat fallbackChat;
    private final AgentStateStore stateStore;
    private final Map<Intent, IntentHandler> handlerMap;

    public AgentOrchestrator(IntentClassifier intentClassifier,
                             FallbackChat fallbackChat,
                             AgentStateStore stateStore,
                             List<IntentHandler> handlers) {
        this.intentClassifier = intentClassifier;
        this.fallbackChat = fallbackChat;
        this.stateStore = stateStore;

        Map<Intent, IntentHandler> map = new EnumMap<>(Intent.class);
        for (IntentHandler handler : handlers) {
            IntentHandler previous = map.put(handler.intent(), handler);
            if (previous != null) {
                throw new IllegalStateException("意图 " + handler.intent()
                        + " 被重复注册：" + previous.getClass().getSimpleName()
                        + " / " + handler.getClass().getSimpleName());
            }
        }
        this.handlerMap = Map.copyOf(map);
        log.info("Agent 编排已启用，已注册处理器：{}", map.keySet());
    }

    public Flux<String> handle(Long userId, String msg) {
        // defer：真正执行推迟到订阅时；配合 subscribeOn 让整段跑在弹性线程池
        return Flux.defer(() -> route(userId, msg))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    log.error("处理请求时出错，userId={}，消息长度={}",
                            userId, msg == null ? 0 : msg.length(), e);
                    log.debug("出错的消息原文：{}", msg);
                    return Flux.just(FALLBACK_REPLY);
                });
    }

    // ==================== 路由 ====================

    private Flux<String> route(Long userId, String msg) {
        AgentState state = stateStore.load(userId);

        // ① 有未完成任务，且用户明确说"算了"
        if (state.stage().isPending() && isGiveUp(msg)) {
            stateStore.clear(userId);
            log.info("用户放弃未完成任务 userId={}，原阶段={}", userId, state.stage());
            return Flux.just(GIVE_UP_REPLY);
        }

        // ② 正在等用户补参数
        if (state.isAsking() && state.missingSlot() != null) {
            return continueAsking(userId, msg, state);
        }

        // ③ 正常流程
        return dispatch(userId, msg, classifySafely(userId, msg));
    }

    /**
     * ASKING 状态下的处理。
     *
     * <p>这是状态机唯一真正"救场"的地方：用户回的是一句裸订单号，单看这句话模型识别不出意图，
     * 但代码知道"我上一轮在等 orderNo"，所以能把它当成参数补上。
     */
    private Flux<String> continueAsking(Long userId, String msg, AgentState state) {
        IntentResult probe = classifySafely(userId, msg);

        // 情况 A：模型也识别成同一个意图（连参数都抠出来了）→ 合并继续
        if (probe.intent() == state.intent()) {
            AgentState merged = state.withSlots(probe.slots());
            log.info("延续上一轮意图 userId={} intent={} slots={}",
                    userId, state.intent(), merged.slots());
            return dispatch(userId, msg, new IntentResult(
                    state.intent(), probe.confidence(), merged.slots(), "延续上一轮"));
        }

        // 情况 B：模型识别不出 → 视为在回答上一轮的问题
        if (probe.isUnknown()) {
            String value = msg == null ? "" : msg.trim();
            Map<String, Object> slots = state.withSlot(state.missingSlot(), value).slots();
            log.info("尝试按补参数处理 userId={} slot={} value={}",
                    userId, state.missingSlot(), value);

            HandlerOutcome outcome;
            try {
                outcome = callHandler(userId, msg, state.intent(), slots);
            } catch (Exception e) {
                log.error("补参数后执行失败 userId={}", userId, e);
                stateStore.clear(userId);
                return Flux.just(FALLBACK_REPLY);
            }

            // 补上之后不再追问 = 真的补对了
            if (outcome != null && !outcome.isAsk()) {
                stateStore.clear(userId);
                return Flux.just(outcome.text());
            }
            // 补上还是不行 → 说明这句不是在补参数，按普通消息处理
            stateStore.clear(userId);
            log.info("补参数无效，按新消息处理 userId={}", userId);
            return fallbackChat.chat(userId, msg);
        }

        // 情况 C：用户其实换了件事 → 放弃旧任务，按新意图走
        stateStore.clear(userId);
        log.info("用户切换到新意图 userId={} 旧={} 新={}",
                userId, state.intent(), probe.intent());
        return dispatch(userId, msg, probe);
    }

    // ==================== 分发 ====================

    private Flux<String> dispatch(Long userId, String msg, IntentResult result) {
        IntentHandler handler = handlerMap.get(result.intent());

        if (handler == null) {
            log.info("意图 {} 暂未纳入编排，降级到原链路", result.intent());
            stateStore.clear(userId);
            return fallbackChat.chat(userId, msg);
        }

        try {
            HandlerOutcome outcome = handler.handle(new AgentContext(userId, msg, result.slots()));
            persistState(userId, result, outcome);
            return Flux.just(outcome.text());
        } catch (Exception e) {
            log.error("处理意图 {} 时出错，userId={}，槽位={}",
                    result.intent(), userId, result.slots(), e);
            stateStore.clear(userId);
            return Flux.just(FALLBACK_REPLY);
        }
    }

    /** 追问就记住"在等哪个参数"，有结果就清空。 */
    private void persistState(Long userId, IntentResult result, HandlerOutcome outcome) {
        if (outcome.isAsk()) {
            stateStore.save(userId, AgentState.idle(userId)
                    .askingFor(result.intent(), result.slots(), outcome.missingSlot()));
            log.info("等待用户补充参数 userId={} intent={} missingSlot={}",
                    userId, result.intent(), outcome.missingSlot());
        } else {
            stateStore.clear(userId);
        }
    }

    /** 直接调处理器，不做状态处理（供"补参数"场景复用）。没注册处理器时返回 null。 */
    private HandlerOutcome callHandler(Long userId, String msg, Intent intent, Map<String, Object> slots) {
        IntentHandler handler = handlerMap.get(intent);
        if (handler == null) {
            return null;
        }
        return handler.handle(new AgentContext(userId, msg, slots));
    }

    // ==================== 工具 ====================

    private IntentResult classifySafely(Long userId, String msg) {
        try {
            IntentResult result = intentClassifier.recognize(msg);
            log.info("意图识别 userId={} intent={} confidence={} slots={} reason={}",
                    userId, result.intent(), result.confidence(), result.slots(), result.reason());
            return result;
        } catch (Exception e) {
            log.warn("意图识别失败，降级到原链路：{}", e.toString());
            return IntentResult.unknown("识别异常");
        }
    }

    private boolean isGiveUp(String msg) {
        if (msg == null) {
            return false;
        }
        String text = msg.trim();
        // 太长的句子不算放弃（"我不要退款了，帮我查订单"这种不该清状态）
        if (text.isEmpty() || text.length() > 10) {
            return false;
        }
        return GIVE_UP_WORDS.stream().anyMatch(text::contains);
    }

    /** 供测试和排查用：当前生效的意图列表 */
    public java.util.Set<Intent> registeredIntents() {
        return handlerMap.keySet();
    }
}
