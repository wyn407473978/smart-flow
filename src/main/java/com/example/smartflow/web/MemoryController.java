package com.example.smartflow.web;

import com.example.smartflow.memory.MemoryRepository;
import com.example.smartflow.memory.MemoryRepository.SummaryRecord;
import com.example.smartflow.memory.MemoryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 调试用：查看会话摘要、查看 / 删除用户长期记忆。
 */
@RestController
@RequestMapping("/api")
public class MemoryController {

    private final MemoryService memory;
    private final MemoryRepository repo;

    public MemoryController(MemoryService memory, MemoryRepository repo) {
        this.memory = memory;
        this.repo = repo;
    }

    /** 会话的滚动摘要，以及该会话绑定用户的长期记忆 */
    @GetMapping("/sessions/{sessionId}/memory")
    public Map<String, Object> sessionMemory(@PathVariable String sessionId) {
        SummaryRecord summary = memory.summary(sessionId);
        String userId = repo.findSessionUser(sessionId).orElse(null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary", summary.summary());
        out.put("upToMessageId", summary.upToMessageId());
        out.put("summarizedCount", summary.summarizedCount());
        out.put("userId", userId);
        out.put("userMemories", memory.userMemories(userId));
        return out;
    }

    @GetMapping("/users/{userId}/memories")
    public Object userMemories(@PathVariable String userId) {
        return memory.userMemories(userId);
    }

    @DeleteMapping("/users/{userId}/memories/{id}")
    public ResponseEntity<Void> deleteUserMemory(@PathVariable String userId, @PathVariable long id) {
        return repo.deleteUserMemoryById(userId, id) > 0
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }
}
