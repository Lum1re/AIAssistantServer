package secKillAI.agent.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.codec.ServerSentEvent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 服务端推送事件的构造器。
 *
 * <p>协议长这样（每行一个事件，事件之间空一行）：
 * <pre>
 *   data:{"type":"stage","stage":"ROUTING"}
 *   data:{"type":"delta","text":"订单 "}
 *   data:{"type":"delta","text":"1997..."}
 *   data:{"type":"done"}
 * </pre>
 *
 * <p><b>为什么用事件流而不是纯文本</b>：
 * <ul>
 *   <li>纯文本时前端只知道"又有字来了"，不知道在干什么、有没有结束、是不是出错</li>
 *   <li>流开始后出错，HTTP 状态码已经发出去了，只能在流内用 {@code error} 事件表达</li>
 * </ul>
 *
 * <p>刻意不用 {@code ServerSentEvent} 的 {@code event:} 字段，统一放在 JSON 的 {@code type} 里 ——
 * 前端只解析一种东西，少一处出错的可能。
 */
public final class SseEvents {

    public static final String TYPE_STAGE = "stage";
    public static final String TYPE_DELTA = "delta";
    public static final String TYPE_ERROR = "error";
    public static final String TYPE_DONE = "done";

    private SseEvents() {
    }

    /** 阶段变化：前端可以据此显示"正在理解你的问题…" */
    public static ServerSentEvent<String> stage(ObjectMapper mapper, String stage) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", TYPE_STAGE);
        payload.put("stage", stage);
        return wrap(mapper, payload);
    }

    /** 一段正文。前端追加显示。 */
    public static ServerSentEvent<String> delta(ObjectMapper mapper, String text) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", TYPE_DELTA);
        payload.put("text", text == null ? "" : text);
        return wrap(mapper, payload);
    }

    /** 流内错误。前端据此显示兜底话术，而不是"半截回复"。 */
    public static ServerSentEvent<String> error(ObjectMapper mapper, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", TYPE_ERROR);
        payload.put("message", message);
        return wrap(mapper, payload);
    }

    /** 结束标记。前端据此收起"正在思考"。 */
    public static ServerSentEvent<String> done(ObjectMapper mapper) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", TYPE_DONE);
        return wrap(mapper, payload);
    }

    private static ServerSentEvent<String> wrap(ObjectMapper mapper, Map<String, Object> payload) {
        String json;
        try {
            json = mapper.writeValueAsString(payload);
        } catch (Exception e) {
            // 序列化失败也不能让流断掉：降级成一条错误事件
            json = "{\"type\":\"error\",\"message\":\"系统有点忙，请稍后再试\"}";
        }
        return ServerSentEvent.<String>builder().data(json).build();
    }
}
