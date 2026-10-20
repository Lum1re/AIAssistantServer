package secKillAI.agent;

import java.util.Map;

/**
 * 意图识别的结果。
 *
 * @param intent     识别出的意图，永不为 null（识别不出来就是 {@link Intent#UNKNOWN}）
 * @param confidence 模型自评的置信度，0~1
 * @param slots      从用户这句话里提取出的业务参数，key 见 {@link IntentRecognizer} 的白名单
 * @param reason     一句话说明为什么这么判断，**只写日志，不进话术**
 */
public record IntentResult(Intent intent, double confidence, Map<String, Object> slots, String reason) {

    public IntentResult {
        if (intent == null) {
            intent = Intent.UNKNOWN;
        }
        slots = slots == null ? Map.of() : Map.copyOf(slots);
        reason = reason == null ? "" : reason;
    }

    /** 识别失败时的兜底值，一律走降级链路。 */
    public static IntentResult unknown(String reason) {
        return new IntentResult(Intent.UNKNOWN, 0d, Map.of(), reason);
    }

    public boolean isUnknown() {
        return intent == Intent.UNKNOWN;
    }
}
