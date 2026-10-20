package secKillAI.agent.handler;

import reactor.core.publisher.Flux;
import secKillAI.agent.AgentContext;
import secKillAI.agent.Intent;

/**
 * 一个意图对应一个处理器。
 *
 * <p>注册方式：实现这个接口 + 加 {@code @Component}，
 * {@link secKillAI.agent.AgentOrchestrator} 会自动收集（构造注入 {@code List<IntentHandler>}）。
 *
 * <p>**没有处理器注册的意图会自动降级到原链路**，所以可以一个意图一个意图地接管，
 * 不会出现「意图识别出来了但没人处理」的裂缝。
 */
public interface IntentHandler {

    /** 这个处理器负责哪个意图。 */
    Intent intent();

    /**
     * 处理一次请求。
     *
     * <p>返回的 Flux 会被直接推给前端。只读场景用 {@code Flux.just(文本)} 即可，
     * 需要模型润色话术时再返回真正的流。
     */
    Flux<String> handle(AgentContext context);
}
