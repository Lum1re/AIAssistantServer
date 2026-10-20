package secKillAI.web.service;

import reactor.core.publisher.Flux;

public interface IAiChatService {
    public Flux<String> chat(String msg, String token);
}
