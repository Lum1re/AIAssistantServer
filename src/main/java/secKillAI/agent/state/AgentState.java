package secKillAI.agent.state;

import com.fasterxml.jackson.annotation.JsonIgnore;
import secKillAI.agent.Intent;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 一个用户的会话进度。
 *
 * <p>它和 MongoDB 里的聊天记录**不是一回事**，职责必须分开：
 * <table border="1">
 *   <tr><th></th><th>聊天记录（MongoDB）</th><th>会话状态（这里）</th></tr>
 *   <tr><td>给谁看</td><td>给模型看</td><td>给代码看</td></tr>
 *   <tr><td>会不会被裁剪</td><td>会（保留最近 20 条）</td><td>不会</td></tr>
 *   <tr><td>丢了会怎样</td><td>模型忘掉上下文，但流程还能走</td><td>流程断掉，用户得重说一遍</td></tr>
 * </table>
 *
 * <p>为什么做成不可变对象（record）：状态迁移只能通过 {@code to/withXxx} 生成新对象，
 * 不存在"某处偷偷把 stage 改了半路"的情况。每次迁移都会刷新 {@code updatedAt}。
 */
public record AgentState(
        Long userId,
        Stage stage,
        Intent intent,
        Map<String, Object> slots,
        String missingSlot,
        String pendingTicket,
        Instant updatedAt
) {

    /** 紧凑构造器：把 null 收敛成安全默认值，避免调用方到处判空。 */
    public AgentState {
        stage = stage == null ? Stage.IDLE : stage;
        slots = slots == null ? Map.of() : Map.copyOf(slots);
        updatedAt = updatedAt == null ? Instant.now() : updatedAt;
    }

    /** 一个全新的空闲状态。读不到 Redis 时返回它。 */
    public static AgentState idle(Long userId) {
        return new AgentState(userId, Stage.IDLE, null, Map.of(), null, null, Instant.now());
    }

    /*
     * 注意：下面三个 isXxx() 方法必须标 @JsonIgnore。
     * 否则 Jackson 会把它们当成"布尔属性"序列化进去（"asking":true 之类），
     * 而 record 的构造器里没有这些参数，反序列化就会抛 UnrecognizedPropertyException。
     * 这个 bug 在线上是静默的：save() 写进垃圾字段、load() 报错后又被容错成 IDLE。
     */

    @JsonIgnore
    public boolean isIdle() {
        return stage == Stage.IDLE;
    }

    /** 是否正在等用户补充参数。 */
    @JsonIgnore
    public boolean isAsking() {
        return stage == Stage.ASKING;
    }

    /** 是否正在等用户确认。 */
    @JsonIgnore
    public boolean isAwaitingConfirm() {
        return stage == Stage.AWAITING_CONFIRM;
    }

    // ==================== 状态迁移（都返回新对象） ====================

    /** 进入"等用户补参数"，并记住在等哪个槽位。 */
    public AgentState askingFor(Intent intent, Map<String, Object> knownSlots, String slotName) {
        return new AgentState(userId, Stage.ASKING, intent, knownSlots,
                slotName, null, Instant.now());
    }

    /** 进入"等用户确认"，并记下确认票据。 */
    public AgentState awaitingConfirm(Intent intent, String ticketId) {
        return new AgentState(userId, Stage.AWAITING_CONFIRM, intent, slots,
                null, ticketId, Instant.now());
    }

    /** 往已知槽位里补一个值（比如用户这一轮补的订单号）。 */
    public AgentState withSlot(String key, Object value) {
        if (key == null || value == null) {
            return this;
        }
        Map<String, Object> copy = new HashMap<>(slots);
        copy.put(key, value);
        return new AgentState(userId, stage, intent, copy, missingSlot, pendingTicket, Instant.now());
    }

    /** 合并一批槽位。 */
    public AgentState withSlots(Map<String, Object> more) {
        if (more == null || more.isEmpty()) {
            return this;
        }
        Map<String, Object> copy = new HashMap<>(slots);
        copy.putAll(more);
        return new AgentState(userId, stage, intent, copy, missingSlot, pendingTicket, Instant.now());
    }

    /** 回到空闲，清掉所有待办信息。任务完成、用户放弃、票据过期时调用。 */
    public AgentState toIdle() {
        return new AgentState(userId, Stage.IDLE, null, Map.of(), null, null, Instant.now());
    }
}
