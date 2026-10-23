package secKillAI.agent.result;

/**
 * 系统级固定话术。
 *
 * <p>集中在一处，避免同一个句子在多个类里各写一遍、改的时候漏掉。
 * 业务类的错误话术在 {@link ErrorCode} 里，这里只放"不属于某个业务错误"的通用回复。
 */
public final class ReplyTemplates {

    /** 内部出错时的兜底。永远不暴露技术细节。 */
    public static final String FALLBACK = "抱歉，处理你的问题时出了点意外，请稍后再试，或者转人工客服。";

    /** 用户放弃未完成任务 */
    public static final String GIVE_UP = "好的，已取消。有别的需要随时说。";

    /** 登录态失效 */
    public static final String LOGIN_EXPIRED = "登录状态已失效，请重新登录后再试。";

    private ReplyTemplates() {
    }
}
