package secKillAI.web.service;

public interface ISessionService {
    // 通过token来找手机号
    Long resolvePhone(String token);
}
