package secKillAI.web.handler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(Exception.class)
    public String handle(Exception e) {
        log.error("AI 客服处理失败", e);
        return "抱歉，客服暂时有点忙，请稍后再试，或者转人工客服。";
    }
}