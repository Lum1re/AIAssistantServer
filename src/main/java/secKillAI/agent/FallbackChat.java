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

    Flux<String> chat(Long userId, String msg);
}
