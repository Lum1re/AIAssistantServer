package secKillAI.agent;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * 一次请求的追踪标识。
 *
 * <p>为什么不用 Servlet 过滤器：本项目的处理跑在 Reactor 的弹性线程池上
 * （{@code subscribeOn(Schedulers.boundedElastic())}），过滤器所在的请求线程到不了那里，
 * ThreadLocal 会丢。所以直接在真正干活的那个线程上生成并放入 MDC。
 *
 * <p>日志格式里已经配了 {@code %X{traceId:-}}，放进去之后同一请求的所有日志都能串起来。
 */
public final class TraceId {

    public static final String KEY = "traceId";

    private TraceId() {
    }

    /** 生成一个 8 位短 id 放进 MDC，返回它（方便在日志里显式打出）。 */
    public static String start() {
        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        MDC.put(KEY, traceId);
        return traceId;
    }

    /** 当前线程的 traceId，没有则返回 "-"。 */
    public static String current() {
        String traceId = MDC.get(KEY);
        return traceId == null ? "-" : traceId;
    }

    /** 线程复用，必须清理，否则下一个请求会带着上一个的 id。 */
    public static void clear() {
        MDC.remove(KEY);
    }
}
