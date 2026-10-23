package secKillAI.agent;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * 意图处理器拿到的上下文。
 *
 * <p>{@code userId} 由服务端从登录态解析后注入，**不经过大模型**，模型无法伪造，
 * 也无法通过槽位指定「查谁的数据」——这是身份隔离的最后一道防线。
 *
 * <p>槽位在这里统一做校验：模型提取出来的值只要不合法，一律当「没提取到」，
 * 交给处理器走追问，而不是带着脏参数去查库。
 */
public record AgentContext(Long userId, String msg, Map<String, Object> slots) {

    /** 订单号：实测是 19 位数字，这里放宽到 15~25 位 */
    private static final Pattern ORDER_NO_PATTERN = Pattern.compile("^\\d{15,25}$");

    public AgentContext {
        slots = slots == null ? Map.of() : slots;
    }

    public boolean has(String key) {
        return str(key) != null;
    }

    /** 取字符串槽位，空串和纯空白都当作没有。 */
    public String str(String key) {
        Object value = slots.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    /**
     * 取场次槽位。
     *
     * <p>只接受 10 / 12 / 14。用户说「11 点有什么」时返回 null，
     * 由处理器回复「我们只有 10、12、14 三个场次」，而不是拿 11 去查一个空结果。
     *
     * <p><b>为什么还要认中文</b>：多轮补参数时，用户回的是「十点」这种口语。
     * 如果只认阿拉伯数字，这句会被当成"没填"，然后无限追问。
     */
    public Integer time() {
        Integer value = intVal("time");
        if (value != null) {
            return isSession(value) ? value : null;
        }
        // 模型没提取出来、或者补参数时整句被塞进来 → 试着从文本里认
        return parseChineseSession(str("time"));
    }

    private boolean isSession(int value) {
        return value == 10 || value == 12 || value == 14;
    }

    /**
     * 从「十点」「十二点场次」「14点」这类文本里认出场次。
     *
     * <p>刻意只认 10/12/14 三种，**不猜**「两点」是 2 点还是 14 点 ——
     * 猜错了会抢错商品，让用户澄清一次代价小得多。
     */
    private Integer parseChineseSession(String text) {
        if (text == null) {
            return null;
        }
        String cleaned = text.replaceAll("[\\s:：点时场次第的]", "");
        switch (cleaned) {
            case "十", "10":
                return 10;
            case "十二", "12":
                return 12;
            case "十四", "14":
                return 14;
            default:
                return null;
        }
    }

    /**
     * 取订单号槽位。
     *
     * <p>只接受 15~25 位纯数字。模型偶尔会把「1997 2331 3725」带空格还回来，
     * 这里去掉空白再校验；仍然不合法就返回 null，让处理器追问。
     */
    public String orderNo() {
        String value = str("orderNo");
        if (value == null) {
            return null;
        }
        String digits = value.replaceAll("\\s+", "");
        return ORDER_NO_PATTERN.matcher(digits).matches() ? digits : null;
    }

    public Long longVal(String key) {
        String value = str(key);
        if (value == null) {
            return null;
        }
        try {
            // 有些模型会把 ID 输出成 123.0
            if (value.endsWith(".0")) {
                value = value.substring(0, value.length() - 2);
            }
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public Integer intVal(String key) {
        Long value = longVal(key);
        if (value == null || value > Integer.MAX_VALUE || value < Integer.MIN_VALUE) {
            return null;
        }
        return value.intValue();
    }
}
