package secKillAI.agent.result;

/**
 * 工具/处理器执行结果的统一表示。
 *
 * <p>把"结果"分成三类，是因为它们的<b>处置方式完全不同</b>：
 * <table border="1">
 *   <tr><th>类型</th><th>含义</th><th>怎么处理</th></tr>
 *   <tr><td>{@link Ok}</td><td>成功，带着业务数据</td><td>渲染成话术给用户</td></tr>
 *   <tr><td>{@link Biz}</td><td>业务失败（订单不存在、售罄…）</td><td>按错误码选话术；不告警</td></tr>
 *   <tr><td>{@link Sys}</td><td>系统故障（数据库挂了、超时…）</td><td>回固定兜底话术；<b>必须告警</b></td></tr>
 * </table>
 *
 * <p>最关键的一条：{@link Sys} <b>永远不把技术细节拼进用户话术</b>。
 * 模型或用户看到"NullPointerException at line 42"没有任何好处，只会被吓到或者被诱导编造原因。
 */
public sealed interface ToolResult<T> {

    /** 成功。data 拿来渲染话术。 */
    record Ok<T>(T data) implements ToolResult<T> {
    }

    /**
     * 业务失败。
     *
     * <p>同时保留 {@code code}（供日志/统计）和 {@code message}（渲染好的话术），
     * 这样调用方不必再 format 一次。
     */
    record Biz<T>(ErrorCode code, String message) implements ToolResult<T> {
    }

    /** 系统故障。message 永远是固定兜底话术，cause 只进日志。 */
    record Sys<T>(String message, Throwable cause) implements ToolResult<T> {
    }

    static <T> ToolResult<T> ok(T data) {
        return new Ok<>(data);
    }

    static <T> ToolResult<T> biz(ErrorCode code, Object... args) {
        return new Biz<>(code, code.format(args));
    }

    static <T> ToolResult<T> sys(Throwable cause) {
        return new Sys<>(ErrorCode.SYS_ERROR.format(), cause);
    }

    default boolean isOk() {
        return this instanceof Ok;
    }

    default boolean isSysError() {
        return this instanceof Sys;
    }
}
