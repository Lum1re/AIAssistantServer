package secKillAI.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 意图识别器：只做「这句话属于哪一类 + 提取业务参数」，**不给模型任何工具**。
 *
 * <p>这是整个改造的关键点：模型在这里没有执行能力，它只能输出一段 JSON。
 * 调不调数据库、调哪个方法，由 {@link AgentOrchestrator} 决定。
 *
 * <p>调用方式复用了项目里已经配好的 {@code StreamingChatLanguageModel}，
 * 把它返回的分片拼成完整文本。好处是**不需要新增任何配置项或依赖**。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IntentRecognizer implements IntentClassifier {

    /**
     * 允许出现在 slots 里的 key。
     *
     * <p>白名单而不是黑名单：模型如果擅自返回 {@code userId}、{@code phone} 这类字段，
     * 会被直接丢弃并打日志。身份永远由服务端注入，不进槽位。
     */
    private static final Set<String> ALLOWED_SLOTS =
            Set.of("time", "orderNo", "seckillId", "productId", "productKeyword");

    private static final String SYSTEM_PROMPT = """
            你是意图分类器，只负责判断意图和提取参数。
            你不回答用户的问题，不做业务处理，不编造任何数据。
            """;

    private final StreamingChatLanguageModel streamingChatLanguageModel;
    private final ObjectMapper objectMapper;

    @Value("${ai.agent.intent.timeout-ms:10000}")
    private long timeoutMs;

    @Value("${ai.agent.intent.min-confidence:0.6}")
    private double minConfidence;

    /** 仅用于排查：超时的时候把当前模型名一起打出来 */
    @Value("${langchain4j.open-ai.streaming-chat-model.model-name:未知}")
    private String modelName;

    /**
     * 识别用户这句话的意图。
     *
     * <p>任何异常都不向外抛——识别失败就是 {@link Intent#UNKNOWN}，
     * 上层会降级到原来的链路，客服不会因此不可用。
     */
    @Override
    public IntentResult recognize(String msg) {
        if (!StringUtils.hasText(msg)) {
            return IntentResult.unknown("消息为空");
        }
        String raw;
        try {
            raw = ask(msg);
        } catch (Exception e) {
            log.warn("意图识别调用失败：{}", e.toString());
            return IntentResult.unknown("调用失败：" + e.getMessage());
        }
        return parse(raw);
    }

    private String ask(String msg) {
        StringBuilder buffer = new StringBuilder();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        List<ChatMessage> messages = List.of(
                new SystemMessage(SYSTEM_PROMPT),
                new UserMessage(buildPrompt(msg)));

        long startAt = System.currentTimeMillis();
        AtomicLong firstTokenAt = new AtomicLong(0);

        streamingChatLanguageModel.chat(messages, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                if (partialResponse != null) {
                    // 记录首字延迟：这是判断"模型慢"还是"根本没连上"的关键
                    if (firstTokenAt.compareAndSet(0L, System.currentTimeMillis())) {
                        log.debug("意图识别收到首个分片，首字延迟 {}ms",
                                firstTokenAt.get() - startAt);
                    }
                    buffer.append(partialResponse);
                }
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                // 有些模型只在 complete 里给全文，分片为空，这里补一次
                if (buffer.length() == 0 && completeResponse != null
                        && completeResponse.aiMessage() != null
                        && completeResponse.aiMessage().text() != null) {
                    buffer.append(completeResponse.aiMessage().text());
                }
                latch.countDown();
            }

            @Override
            public void onError(Throwable error) {
                failure.set(error);
                latch.countDown();
            }
        });

        try {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                // 把"收到多少字、首字等了多久"写进异常，
                // 这样一眼能区分：一个分片都没有 = 连不上/鉴权失败；有分片但没写完 = 太慢
                String progress = firstTokenAt.get() == 0L
                        ? "一个分片都没收到，多半是 API Key 无效、模型名不存在，或网络到不了 DashScope"
                        : "已收到 " + buffer.length() + " 个字符，首字延迟 "
                                + (firstTokenAt.get() - startAt) + "ms，只是没在时限内写完";
                throw new IllegalStateException("意图识别超时（" + timeoutMs + "ms）："
                        + progress + "。当前模型=" + modelName);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("意图识别被中断", e);
        }
        if (failure.get() != null) {
            throw new IllegalStateException("意图识别调用失败", failure.get());
        }
        return buffer.toString();
    }

    private String buildPrompt(String msg) {
        StringBuilder sb = new StringBuilder();
        sb.append("判断用户这句话属于下面哪个意图，并提取业务参数。\n\n");
        sb.append("可选意图（只能选一个）：\n");
        for (Intent intent : Intent.values()) {
            sb.append("- ").append(intent.name()).append("：")
                    .append(intent.description()).append('\n');
        }
        sb.append("""
                
                参数提取规则：
                - time：只能是 10、12、14 三个数字。用户没说，或者说的不是这三个值，就不要填
                - orderNo：15~25 位纯数字，只能是用户原话里出现的数字串，绝对不能编造
                - seckillId / productId：只有用户明确给出数字编号时才填
                - productKeyword：用户提到的商品名称或关键词，保留原话，不要翻译或改写
                - 提取不到的参数直接省略，宁可不填，也不要猜
                
                输出要求：只输出一行 JSON，不要 Markdown 代码块，不要任何解释文字：
                {"intent":"<意图名>","confidence":<0到1的小数>,"slots":{<参数>},"reason":"<一句话原因>"}
                
                用户消息：
                """);
        sb.append(msg);
        return sb.toString();
    }

    /**
     * 解析模型返回的 JSON。
     *
     * <p>模型经常会裹一层 ```json 或者加一句「好的，这是结果：」，
     * 所以这里不按整串解析，而是截出第一个 { 到最后一个 } 之间的内容。
     */
    IntentResult parse(String raw) {
        if (!StringUtils.hasText(raw)) {
            return IntentResult.unknown("模型返回为空");
        }
        String json = extractJson(raw);
        if (json == null) {
            log.warn("意图识别返回的不是 JSON：{}", raw);
            return IntentResult.unknown("返回格式不是 JSON");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            log.warn("意图识别 JSON 解析失败：{}，原文：{}", e.getMessage(), raw);
            return IntentResult.unknown("JSON 解析失败");
        }

        Intent intent = Intent.parse(root.path("intent").asText(null));
        double confidence = root.path("confidence").asDouble(1.0d);
        String reason = root.path("reason").asText("");

        Map<String, Object> slots = new LinkedHashMap<>();
        JsonNode slotsNode = root.path("slots");
        if (slotsNode.isObject()) {
            slotsNode.fields().forEachRemaining(entry -> {
                String key = entry.getKey();
                if (!ALLOWED_SLOTS.contains(key)) {
                    // 模型擅自塞了身份类字段，直接丢掉
                    log.warn("忽略未定义的槽位：{}（值：{}）", key, entry.getValue());
                    return;
                }
                Object value = objectMapper.convertValue(entry.getValue(), Object.class);
                if (value != null && StringUtils.hasText(String.valueOf(value))) {
                    slots.put(key, value);
                }
            });
        }

        if (confidence < minConfidence) {
            log.info("意图 {} 置信度 {} 低于阈值 {}，按 UNKNOWN 处理", intent, confidence, minConfidence);
            return new IntentResult(Intent.UNKNOWN, confidence, Map.of(), reason);
        }

        return new IntentResult(intent, confidence, slots, reason);
    }

    private String extractJson(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return raw.substring(start, end + 1);
    }
}
