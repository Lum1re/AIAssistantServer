package secKillAI.web.controller;

import lombok.RequiredArgsConstructor;
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
    @RequestMapping("/chat")
    public Flux<String> chat(@RequestParam("msg") String msg,
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
