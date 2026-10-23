package secKillAI.agent.confirm;

import java.util.List;

/**
 * 判断用户这句话是"确认"、"否掉"，还是"别的意思"。
 *
 * <p><b>纯规则，不调模型。</b>这是整个改造最关键的一条边界：
 * 以前"用户是否确认"由大模型判断，模型可能因为上下文误读而自己决定；
 * 现在由这段代码判断，规则是确定的、可测的、可复盘的。
 *
 * <p>判定顺序（不能变）：
 * <ol>
 *   <li><b>先看否定词</b>："不退了"里也有"退"，必须先判 NO</li>
 *   <li>再看长度：太长的句子一律 NONE（"确认一下这个订单能退吗"不是确认，是提问）</li>
 *   <li>最后看确认词</li>
 * </ol>
 *
 * <p><b>铁律：宁可判 {@link Verdict#NONE}，不可误判 {@link Verdict#YES}。</b>
 * 判 NONE 只是多问用户一句"还要继续吗"；误判 YES 是资金操作。
 */
public final class ConfirmRules {

    /** 判定结果 */
    public enum Verdict {
        /** 明确同意 */
        YES,
        /** 明确否定 / 放弃 */
        NO,
        /** 说不准，或是在说别的事 */
        NONE
    }

    /** 否定词。句子里出现任意一个就判 NO。 */
    private static final List<String> NO_WORDS = List.of(
            "不", "别", "算", "取消", "等一下", "等等", "再说", "先不", "停");

    /**
     * 短确认词：必须<b>整句等于</b>才算。
     *
     * <p>为什么不能"包含"：{@code "行"} 会命中"银行""行情"，{@code "是"} 会命中"是不是"。
     * 单字/双字的词必须整句比对，否则误判率高得没法用。
     */
    private static final List<String> YES_EXACT = List.of(
            "好", "好的", "好吧", "好嘞", "行", "行吧", "可以", "是", "是的", "对", "嗯", "继续");

    /**
     * 长确认词：出现在短句里就算确认。
     *
     * <p>这些词本身语义明确，不太会被别的意思包含。
     */
    private static final List<String> YES_CONTAINS = List.of(
            "确认", "确定", "同意", "没问题", "退吧", "买吧", "办吧", "退款吧", "下单吧");

    /** 超过这个长度（去标点后）就不当确认处理。 */
    private static final int MAX_CONFIRM_LENGTH = 10;

    private ConfirmRules() {
    }

    /**
     * 判断一句话的含义。
     *
     * @return 永远不返回 null；无法判断时返回 {@link Verdict#NONE}
     */
    public static Verdict judge(String msg) {
        String text = normalize(msg);
        if (text.isEmpty()) {
            return Verdict.NONE;
        }

        // ① 否定优先："不退了" 里也有"退"，必须先判 NO
        for (String word : NO_WORDS) {
            if (text.contains(word)) {
                return Verdict.NO;
            }
        }

        // ② 太长的句子不算确认（那是提问或闲聊）
        if (text.length() > MAX_CONFIRM_LENGTH) {
            return Verdict.NONE;
        }

        // ③ 短确认词：必须整句相等
        if (YES_EXACT.contains(text)) {
            return Verdict.YES;
        }

        // ④ 长确认词：包含即可
        for (String phrase : YES_CONTAINS) {
            if (text.contains(phrase)) {
                return Verdict.YES;
            }
        }
        return Verdict.NONE;
    }

    /**
     * 去掉所有空白、标点、符号，只留文字和数字。
     *
     * <p>这样"确认。""确 认""确认！"都能被同一条规则处理。
     */
    static String normalize(String msg) {
        if (msg == null) {
            return "";
        }
        return msg.replaceAll("[^\\p{L}\\p{N}]", "");
    }
}
