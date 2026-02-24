package com.jmal.clouddisk.controller.sse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@Slf4j
public class SseController {

    /**
     * 每个用户最大SSE连接数
     */
    private static final int MAX_CONNECTIONS_PER_USER = 30;

    /**
     * SSE连接超时时间(毫秒), 30秒
     */
    private static final long SSE_TIMEOUT = 30 * 1000L;

    /**
     * key: uuid
     * value: SseEmitter
     */
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    /**
     * key: username
     * value: uuid list
     */
    private final Map<String, Set<String>> users = new ConcurrentHashMap<>();

    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@RequestParam String username, @RequestParam String uuid) {
        Set<String> uuids = users.computeIfAbsent(username, k -> ConcurrentHashMap.newKeySet());
        // 限制每个用户的最大连接数
        if (uuids.size() >= MAX_CONNECTIONS_PER_USER) {
            // 清理已失效的uuid（对应的emitter已不存在）
            uuids.removeIf(existingUuid -> !emitters.containsKey(existingUuid));
            if (uuids.size() >= MAX_CONNECTIONS_PER_USER) {
                log.warn("用户 {} 的SSE连接数超过最大限制 {}", username, MAX_CONNECTIONS_PER_USER);
                return null;
            }
        }
        uuids.add(uuid);

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        emitters.put(uuid, emitter);
        // 连接完成或超时时，同时清理emitters和users中的uuid
        Runnable cleanup = () -> {
            emitters.remove(uuid);
            Set<String> userUuids = users.get(username);
            if (userUuids != null) {
                userUuids.remove(uuid);
            }
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());
        return emitter;
    }

    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public ResponseEntity<String> handleAsyncRequestTimeoutException() {
        // 处理异步请求超时异常,例如记录日志或返回自定义响应

        // 设置响应头的 Content-Type 为 "text/event-stream"
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_EVENT_STREAM);

        // 返回一个空的 SSE 事件作为响应
        String emptyEvent = "event: timeout\ndata: \n\n";
        return new ResponseEntity<>(emptyEvent, headers, HttpStatus.OK);
    }

    @PostMapping("/send")
    public void sendEvent(@RequestBody Message message) {
        String username = message.getUsername();
        Set<String> uuids = users.get(username);
        if (uuids != null) {
            // 使用副本遍历，避免并发修改异常
            List<String> uuidList = new ArrayList<>(uuids);
            uuidList.forEach(uuid -> sendMessage(message, uuid));
        }
    }

    private void sendMessage(Object message, String uuid) {
        SseEmitter emitter = emitters.get(uuid);
        if (emitter != null) {
            try {
                emitter.send(message);
            } catch (IllegalStateException e) {
                if (e.getMessage() != null && e.getMessage().contains("completed")) {
                    removeEmitter(uuid);
                }
            } catch (IOException e) {
                // 日志记录或其他处理
                removeEmitter(uuid);
                if (e.getMessage() != null && e.getMessage().contains("Broken pipe")) {
                    log.warn("Broken pipe: {}", uuid);
                } else {
                    // 处理其他IO异常
                    log.error("Failed to send event to uuid: {}", uuid, e);
                }
            }
        }
    }

    /**
     * 统一清理 emitter：同时从 emitters 和 users 中移除 uuid，避免内存泄漏
     */
    private void removeEmitter(String uuid) {
        emitters.remove(uuid);
        // 同步清理 users 中的 uuid，防止 IOException/IllegalStateException 时只清了 emitters 导致 users 累积
        users.values().forEach(uuids -> uuids.remove(uuid));
    }

    /**
     * 每10秒发送一次心跳消息
     */
    @Scheduled(fixedRate = 10000)
    public void heartbeat() {
        emitters.forEach((uuid, emitter) -> sendMessage("h", uuid));
    }
}
