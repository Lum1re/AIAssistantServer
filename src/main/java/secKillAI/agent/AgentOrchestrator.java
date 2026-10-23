package secKillAI.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import secKillAI.agent.confirm.ConfirmRules;
import secKillAI.agent.confirm.ConfirmTicket;
import secKillAI.agent.confirm.ConfirmTicketStore;
import secKillAI.agent.handler.ConfirmableHandler;
import secKillAI.agent.handler.HandlerOutcome;
import secKillAI.agent.handler.IntentHandler;
import secKillAI.agent.result.ErrorCode;
import secKillAI.agent.result.ReplyTemplates;
import secKillAI.agent.state.AgentLock;
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

    /** 上一条还没处理完时的回复。话术模板统一放在 ErrorCode / ReplyTemplates 里。 */
    private static final String BUSY_REPLY = ErrorCode.BUSY.format();

    /** 待办状态下，用户说这些词就视为放弃（带长度限制，避免长句被误判） */
    private static final List<String> GIVE_UP_WORDS =
            List.of("算了", "取消", "不查了", "不用了", "不要了", "先不", "不了", "停");

    private final IntentClassifier intentClassifier;
    private final FallbackChat fallbackChat;
    private final AgentStateStore stateStore;
    private final AgentLock agentLock;
    private final ConfirmTicketStore ticketStore;
    private final Map<Intent, IntentHandler> handlerMap;

    public AgentOrchestrator(IntentClassifier intentClassifier,
                             FallbackChat fallbackChat,
                             AgentStateStore stateStore,
                             AgentLock agentLock,
                             ConfirmTicketStore ticketStore,
                             List<IntentHandler> handlers) {
        this.intentClassifier = intentClassifier;
        this.fallbackChat = fallbackChat;
        this.stateStore = stateStore;
        this.agentLock = agentLock;
        this.ticketStore = ticketStore;

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
        return Flux.defer(() -> {
                    // traceId 在这里生成：本方法之后的所有日志都跑在当前线程上，
                    // 所以 MDC 能一路带下去（ServletRequest 线程到不了这里，故不依赖过滤器）。
                    String traceId = TraceId.start();
                    long startAt = System.currentTimeMillis();
                    try {
                        // 同一用户同一时间只处理一条：并发改同一份状态会把状态改乱
                        String lockToken = agentLock.tryLock(userId);
                        if (lockToken == null) {
                            log.info("并发请求被拒绝 userId={}", userId);
                            TraceId.clear();
                            return Flux.just(BUSY_REPLY);
                        }
                        // 完成 / 出错 / 前端取消，三种结束方式都要释放锁；TTL 只是兜底
                        return route(userId, msg)
                                .doFinally(signal -> {
                                    log.info("请求结束 userId={} trace={} signal={} cost={}ms",
                                            userId, traceId, signal, System.currentTimeMillis() - startAt);
                                    agentLock.unlock(userId, lockToken);
                                    TraceId.clear();
                                });
                    } catch (RuntimeException e) {
                        TraceId.clear();
                        throw e;
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    log.error("处理请求时出错，userId={}，消息长度={}",
                            userId, msg == null ? 0 : msg.length(), e);
                    log.debug("出错的消息原文：{}", msg);
                    return Flux.just(ReplyTemplates.FALLBACK);
                });
    }

    // ==================== 路由 ====================

    private Flux<String> route(Long userId, String msg) {
        AgentState state = stateStore.load(userId);

        // ① 有未完成任务，且用户明确说"算了"
        if (state.stage().isPending() && isGiveUp(msg)) {
            stateStore.clear(userId);
            // 如果待办是"等确认"，票据也要一起作废，否则它会白占 5 分钟
            ticketStore.clear(userId);
            log.info("用户放弃未完成任务 userId={}，原阶段={}", userId, state.stage());
            return Flux.just(ReplyTemplates.GIVE_UP);
        }

        // ② 正在等用户确认一个写操作
        if (state.isAwaitingConfirm()) {
            return handleConfirm(userId, msg, state);
        }

        // ③ 正在等用户补参数
        if (state.isAsking() && state.missingSlot() != null) {
            return continueAsking(userId, msg, state);
        }

        // ③ 正常流程
        return dispatch(userId, msg, classifySafely(userId, msg));
    }

    // ==================== 等确认 ====================

    /**
     * AWAITING_CONFIRM 状态下的处理。
     *
     * <p>用户这句话只有三种可能：确认、否定、或者插了别的话。
     * 判断完全由 {@link ConfirmRules} 的规则做，<b>不经过模型</b> ——
     * 这正是这次改造最核心的那条边界。
     */
    private Flux<String> handleConfirm(Long userId, String msg, AgentState state) {
        ConfirmRules.Verdict verdict = ConfirmRules.judge(msg);

        // ① 确认 → 消费票据并执行
        if (verdict == ConfirmRules.Verdict.YES) {
            return executePending(userId, msg);
        }

        // ② 否定 → 作废票据
        if (verdict == ConfirmRules.Verdict.NO) {
            ticketStore.clear(userId);
            stateStore.clear(userId);
            log.info("用户否定了待确认操作 userId={}，原阶段={}", userId, state.stage());
            return Flux.just(ReplyTemplates.GIVE_UP);
        }

        // ③ 说不准 → 当作插话：正常回答，但把待确认状态恢复回去。
        //    这样用户问完"这单什么时候买的"之后，仍然可以说"确认"。
        log.info("待确认中用户插话，票据保留 userId={}", userId);
        Flux<String> answer = dispatch(userId, msg, classifySafely(userId, msg));
        stateStore.save(userId, state);
        return answer;
    }

    /**
     * 消费票据并执行。**这是整个系统唯一真正触发写操作的地方。**
     *
     * <p>几个刻意的设计：
     * <ul>
     *   <li>票据"取出即删"，同一张确认只能被执行一次</li>
     *   <li>执行用的参数来自票据，<b>不来自模型这一次的输出</b></li>
     *   <li>先清状态再执行：即使执行失败，也不会让用户卡在"等确认"里</li>
     * </ul>
     */
    private Flux<String> executePending(Long userId, String msg) {
        ConfirmTicket ticket = ticketStore.take(userId);
        stateStore.clear(userId);

        if (ticket == null) {
            log.info("确认时票据不存在或已过期 userId={}", userId);
            return Flux.just(ErrorCode.CONFIRM_EXPIRED.format());
        }

        IntentHandler handler = handlerMap.get(ticket.intent());
        if (!(handler instanceof ConfirmableHandler confirmable)) {
            log.error("意图 {} 未实现 ConfirmableHandler，票据作废 userId={}",
                    ticket.intent(), userId);
            return Flux.just(ReplyTemplates.FALLBACK);
        }

        try {
            HandlerOutcome outcome = confirmable.execute(
                    new AgentContext(userId, msg, ticket.payload()), ticket);
            log.info("待确认操作已执行 userId={} intent={} ticket={}",
                    userId, ticket.intent(), ticket.ticketId());
            return Flux.just(outcome.text());
        } catch (Exception e) {
            log.error("执行待确认操作失败 userId={} intent={} ticket={}",
                    userId, ticket.intent(), ticket.ticketId(), e);
            return Flux.just(ReplyTemplates.FALLBACK);
        }
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

        // 情况 B：**不管模型识别成什么**，先试一次"这句话就是在补参数"。
        //
        // 为什么要把"UNKNOWN"和"识别成别的意图"合到一起：
        // 用户在回答"几点场次"时说的是「十点」，模型很可能把它判成 QUERY_PRODUCTS。
        // 如果这时直接按"用户换话题了"处理，任务就被白白扔掉 —— 见下面的情况 C。
        String value = msg == null ? "" : msg.trim();
        Map<String, Object> slots = state.withSlot(state.missingSlot(), value).slots();
        log.info("尝试按补参数处理 userId={} slot={} value={} 模型判成了={}",
                userId, state.missingSlot(), value, probe.intent());

        HandlerOutcome outcome;
        try {
            outcome = callHandler(userId, msg, state.intent(), slots);
        } catch (Exception e) {
            log.error("补参数后执行失败 userId={}", userId, e);
            stateStore.clear(userId);
            return Flux.just(ReplyTemplates.FALLBACK);
        }

        // 补上之后不再追问 = 真的补对了
        if (outcome != null && !outcome.isAsk()) {
            stateStore.clear(userId);
            return Flux.just(outcome.text());
        }

        // 情况 C：补不进去 → 说明这句确实不是补参数，按新消息处理
        stateStore.clear(userId);
        log.info("补参数无效，按新消息处理 userId={} 模型判成了={}", userId, probe.intent());
        return dispatch(userId, msg, probe);
    }

    // ==================== 分发 ====================

    private Flux<String> dispatch(Long userId, String msg, IntentResult result) {
        IntentHandler handler = handlerMap.get(result.intent());

        if (handler == null) {
            // 审计：这是"多少请求落在降级"的唯一数据来源，上线后靠它判断解析率
            log.info("audit intent={} handler=NONE result=DEGRADED userId={}",
                    result.intent(), userId);
            stateStore.clear(userId);
            // 降级一律只读：意图没识别出来时，绝不能把写操作的权限还给模型，
            // 否则只要降级一次，"确认由代码判定"就退回到"确认由模型判定"了。
            return fallbackChat.chat(userId, msg, true);
        }

        long startAt = System.currentTimeMillis();
        try {
            HandlerOutcome outcome = handler.handle(new AgentContext(userId, msg, result.slots()));
            persistState(userId, result, outcome);
            // 审计：意图 / 处理器 / 结局 / 耗时，一行一条，方便统计
            log.info("audit intent={} handler={} result={} cost={}ms userId={}",
                    result.intent(), handler.getClass().getSimpleName(), outcomeType(outcome),
                    System.currentTimeMillis() - startAt, userId);
            return Flux.just(outcome.text());
        } catch (Exception e) {
            log.info("audit intent={} handler={} result=ERROR cost={}ms userId={}",
                    result.intent(), handler.getClass().getSimpleName(),
                    System.currentTimeMillis() - startAt, userId);
            log.error("处理意图 {} 时出错，userId={}，槽位={}",
                    result.intent(), userId, result.slots(), e);
            stateStore.clear(userId);
            return Flux.just(ReplyTemplates.FALLBACK);
        }
    }

    /** 追问就记住"在等哪个参数"，等确认就记住票据，有结果就清空。 */
    private void persistState(Long userId, IntentResult result, HandlerOutcome outcome) {
        if (outcome.isConfirm()) {
            stateStore.save(userId, AgentState.idle(userId)
                    .awaitingConfirm(result.intent(), outcome.pendingTicket()));
            log.info("等待用户确认 userId={} intent={} ticket={}",
                    userId, result.intent(), outcome.pendingTicket());
        } else if (outcome.isAsk()) {
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

    /** 审计用：处理器这次给了什么结局 */
    private String outcomeType(HandlerOutcome outcome) {
        if (outcome.isConfirm()) {
            return "CONFIRM";
        }
        if (outcome.isAsk()) {
            return "ASK";
        }
        return "REPLY";
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
