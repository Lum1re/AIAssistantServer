package secKillAI.web.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import secKillAI.mongo.store.MemoryStore;
import secKillAI.web.service.IAiChatService;
import secKillAI.web.service.ISessionService;

@RestController
@RequestMapping("/seckillAgent")
@RequiredArgsConstructor
public class SecKillController {

    private final IAiChatService aiChatService;
    private final ISessionService sessionService;
    private final MemoryStore memoryStore;

    /**
     * 流式对话，返回 SSE 事件流。
     *
     * <p>{@code produces = text/event-stream} 是关键：告诉浏览器这是事件流，
     * 不要等全部内容生成完再一次性返回。
     */
    @GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chat(
            @RequestParam("msg") String msg,
            @RequestHeader(value = "token", required = false) String token) {
        return aiChatService.chat(msg, token);
    }

    @PostMapping("/session/clear")
    public String clear(@RequestHeader(value = "token", required = false) String token) {
        Long phone = sessionService.resolvePhone(token);
        if (phone == null) {
            return "登录状态已失效";
        }
        memoryStore.deleteMessages(phone);
        return "会话已清空";
    }
}
