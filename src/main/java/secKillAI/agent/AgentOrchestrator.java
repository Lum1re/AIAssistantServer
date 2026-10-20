package secKillAI.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import secKillAI.agent.handler.IntentHandler;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 编排器：把「模型判断意图」变成「代码决定执行」。
 *
 * <p>一次请求的流程：
 * <pre>
 *   用户消息
 *     → IntentRecognizer（模型，只分类，不给工具）
 *     → 按 Intent 查表找 IntentHandler（纯代码）
 *     → 查到：执行处理器，话术由代码拼装
 *     → 没查到：降级到 LegacyChatAgent（原来的全工具链路）
 * </pre>
 *
 * <p>这样做的收益是「可以测试」：把 {@link IntentRecognizer} 换成一个返回固定结果的假实现，
 * 就能在不联网、不花 token 的情况下验证「用户说这句话，系统会不会做错事」。
 * 见 {@code AgentOrchestratorTest}。
 *
 * <p>阻塞说明：意图识别是一次同步等待的模型调用，必须放在
 * {@link Schedulers#boundedElastic()} 上跑，否则会堵住 WebFlux 的事件循环线程。
 */
@Slf4j
@Component
public class AgentOrchestrator {

    private final IntentClassifier intentClassifier;
    private final FallbackChat fallbackChat;
    private final Map<Intent, IntentHandler> handlerMap;

    /**
     * 构造时就把「意图 → 处理器」的表建好。
     *
     * <p>刻意不用 {@code @PostConstruct}：表在构造完就应该是完整的，
     * 否则对象存在一个「能用但还没初始化」的中间状态，测试时也得记着手动调 init()。
     */
    public AgentOrchestrator(IntentClassifier intentClassifier,
                             FallbackChat fallbackChat,
                             List<IntentHandler> handlers) {
        this.intentClassifier = intentClassifier;
        this.fallbackChat = fallbackChat;

        Map<Intent, IntentHandler> map = new EnumMap<>(Intent.class);
        for (IntentHandler handler : handlers) {
            IntentHandler previous = map.put(handler.intent(), handler);
            if (previous != null) {
                // 同一个意图被注册两次属于配置错误，直接让启动失败，别留到线上才发现
                throw new IllegalStateException("意图 " + handler.intent()
                        + " 被重复注册：" + previous.getClass().getSimpleName()
                        + " / " + handler.getClass().getSimpleName());
            }
        }
        this.handlerMap = Map.copyOf(map);
        log.info("Agent 编排已启用，已注册处理器：{}", map.keySet());
    }

    public Flux<String> handle(Long userId, String msg) {
        return Mono
                // ① 意图识别：阻塞调用，放到弹性线程池
                .fromCallable(() -> {
                    IntentResult result = intentClassifier.recognize(msg);
                    log.info("意图识别 userId={} intent={} confidence={} slots={} reason={}",
                            userId, result.intent(), result.confidence(), result.slots(), result.reason());
                    return result;
                })
                .subscribeOn(Schedulers.boundedElastic())
                // ② 识别失败不让用户看到异常，一律降级
                .onErrorResume(e -> {
                    log.warn("意图识别失败，降级到原链路：{}", e.toString());
                    return Mono.just(IntentResult.unknown("识别异常"));
                })
                // ③ 按意图分发
                .flatMapMany(result -> dispatch(userId, msg, result))
                // ④ 流兜底：一旦开始推送数据，@RestControllerAdvice 就管不到了
                //    （HTTP 响应头已发出，前端只会收到半截文字）。
                //    降级链路返回的是真正的流，它的异步错误只能在这里拦。
                .onErrorResume(e -> {
                    log.error("处理请求时出错，userId={}，msg={}", userId, msg, e);
                    return Flux.just("抱歉，处理你的问题时出了点意外，请稍后再试，或者转人工客服。");
                });
    }

    private Flux<String> dispatch(Long userId, String msg, IntentResult result) {
        IntentHandler handler = handlerMap.get(result.intent());

        if (handler == null) {
            log.info("意图 {} 暂未纳入编排，降级到原链路", result.intent());
            return fallbackChat.chat(userId, msg);
        }

        try {
            return handler.handle(new AgentContext(userId, msg, result.slots()));
        } catch (Exception e) {
            // 处理器是同步拼话术的，异常会在这里抛出，不能让它落入响应式流变成半截响应
            log.error("处理意图 {} 时出错，userId={}，槽位={}",
                    result.intent(), userId, result.slots(), e);
            return Flux.just("抱歉，处理你的问题时出了点意外，请稍后再试，或者转人工客服。");
        }
    }

    /** 供测试和排查用：当前生效的意图列表 */
    public java.util.Set<Intent> registeredIntents() {
        return handlerMap.keySet();
    }
}
