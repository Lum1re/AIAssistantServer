package secKillAI.web.service;

import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

public interface IAiChatService {

    /**
     * 流式对话，返回 SSE 事件流（stage / delta / error / done）。
     *
     * @param msg   用户消息
     * @param token 登录令牌（身份只从这里来）
     */
    Flux<ServerSentEvent<String>> chat(String msg, String token);
}
