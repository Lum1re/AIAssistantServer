package secKillAI.agent.state;

/**
 * 会话所处的阶段。
 *
 * <p>只保留**需要跨请求记住**的状态。像"正在识别意图"（ROUTING）、"正在执行"（EXECUTING）
 * 这类瞬态的中间过程，只存在于一次方法调用栈里（几毫秒），存进 Redis 没有意义，
 * 反而会因为"存了没来得及改"造成状态错乱。所以它们不是这里的枚举值。
 *
 * <p>为什么需要跨请求记住？因为一次任务常常要用户配合两三轮：
 * <pre>
 *   用户：帮我查订单
 *   系统：请提供订单号…          ← 此时状态必须变成 ASKING，并记住"在等 orderNo"
 *   用户：1997 2331 ...          ← 下一轮才知道这句话是在补参数，不是新请求
 * </pre>
 */
public enum Stage {

    /** 空闲：没有未完成的任务。Redis 里通常没有这个 key（读不到就是 IDLE）。 */
    IDLE("空闲"),

    /** 等用户补充参数。{@code missingSlot} 记录在等哪一个。 */
    ASKING("等待用户补充参数"),

    /** 等用户确认一个写操作。{@code pendingTicket} 指向服务端暂存的确认票据。 */
    AWAITING_CONFIRM("等待用户确认");

    private final String label;

    Stage(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** 是否是一个"有未完成任务"的状态。 */
    public boolean isPending() {
        return this != IDLE;
    }
}
