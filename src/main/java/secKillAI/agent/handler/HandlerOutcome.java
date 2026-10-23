package secKillAI.agent.handler;

/**
 * 处理器的返回值：一段话术 + 三种"后续动作"之一。
 *
 * <p>为什么不再直接返回 Flux&lt;String&gt;：编排器必须区分三种情况，
 * 它们的后续处理完全不同：
 * <table border="1">
 *   <tr><th>工厂方法</th><th>含义</th><th>编排器做什么</th></tr>
 *   <tr><td>{@link #reply}</td><td>本次任务办完了</td><td>清空状态</td></tr>
 *   <tr><td>{@link #ask}</td><td>缺参数，等用户补</td><td>存 stage=ASKING + missingSlot</td></tr>
 *   <tr><td>{@link #confirm}</td><td>有写操作待用户确认</td><td>存 stage=AWAITING_CONFIRM + pendingTicket</td></tr>
 * </table>
 *
 * <p>如果只用"一段文字"，编排器只能靠猜（看结尾是不是问号？），
 * 一改文案就出错。这三个字段就是显式契约。
 */
public record HandlerOutcome(String text, String missingSlot, String pendingTicket) {

    /** 最终答案，本次任务结束（成功或失败都算结束）。 */
    public static HandlerOutcome reply(String text) {
        return new HandlerOutcome(text, null, null);
    }

    /** 追问：请用户补充某个槽位。 */
    public static HandlerOutcome ask(String text, String slot) {
        return new HandlerOutcome(text, slot, null);
    }

    /**
     * 等用户确认：已经生成了服务端票据，用户回"确认"后才执行。
     *
     * @param ticketId 票据 id，由处理器创建后传回来，编排器只负责记进状态
     */
    public static HandlerOutcome confirm(String text, String ticketId) {
        return new HandlerOutcome(text, null, ticketId);
    }

    public boolean isAsk() {
        return missingSlot != null;
    }

    public boolean isConfirm() {
        return pendingTicket != null;
    }
}
