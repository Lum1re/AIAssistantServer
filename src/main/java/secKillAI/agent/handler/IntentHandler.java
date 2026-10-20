package secKillAI.agent.handler;

import secKillAI.agent.AgentContext;
import secKillAI.agent.Intent;

/**
 * 一个意图对应一个处理器。
 *
 * <p>注册方式：实现这个接口 + 加 {@code @Component}，
 * {@link secKillAI.agent.AgentOrchestrator} 会自动收集（构造注入 {@code List<IntentHandler>}）。
 *
 * <p><b>没有处理器注册的意图会自动降级到原链路</b>，所以可以一个意图一个意图地接管。
 */
public interface IntentHandler {

    /** 这个处理器负责哪个意图。 */
    Intent intent();

    /**
     * 处理一次请求。
     *
     * <p>同步返回：查库 + 拼话术都在这里做完。需要向用户追问时返回
     * {@link HandlerOutcome#ask(String, String)}——编排器据此记住会话状态
     * （stage=ASKING、missingSlot=xxx），下一轮才知道用户在补哪个参数。
     */
    HandlerOutcome handle(AgentContext context);
}
