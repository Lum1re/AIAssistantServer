package secKillAI.agent.handler;

/**
 * 处理器的返回值：一段话术 + 可选的"还缺哪个参数"。
 *
 * <p>为什么不再直接返回 Flux&lt;String&gt;：
 * 编排器需要区分两种情况——"这是最终答案"和"我在追问，请记住在等什么"。
 * 字符串里没有这个信息，只能靠约定（比如判断结尾是不是问号），非常脆弱。
 */
public record HandlerOutcome(String text, String missingSlot) {

    /** 最终答案，本次任务结束。 */
    public static HandlerOutcome reply(String text) {
        return new HandlerOutcome(text, null);
    }

    /** 追问：请用户补充某个槽位，编排器会把 stage=ASKING 记进会话状态。 */
    public static HandlerOutcome ask(String text, String slot) {
        return new HandlerOutcome(text, slot);
    }

    public boolean isAsk() {
        return missingSlot != null;
    }
}
