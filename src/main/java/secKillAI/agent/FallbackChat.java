package secKillAI.agent;

import reactor.core.publisher.Flux;

/**
 * 降级链路。
 *
 * <p>意图识别不出来、或者某个意图还没有编排实现时，交给它处理，
 * 也就是改造前那套「模型自由调用工具」的行为。
 *
 * <p>抽成接口是为了让编排器不直接依赖 LangChain4j 的具体装配，
 * 这样测试可以替换成一个只记录「有没有被调用」的假实现。
 */
public interface FallbackChat {

    /**
     * @param userId   手机号（服务端注入，模型接触不到）
     * @param msg      用户原话
     * @param readonly {@code true} 表示只给只读工具（查询类），不给下单/退款工具。
     *                 见 {@link LegacyChatAgent} 里的说明。
     */
    Flux<String> chat(Long userId, String msg, boolean readonly);
}
