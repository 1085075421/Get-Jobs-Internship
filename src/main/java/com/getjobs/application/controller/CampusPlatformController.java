package com.getjobs.application.controller;

import com.getjobs.application.service.CampusPlatformService;
import com.getjobs.application.service.ZhilianService;
import com.getjobs.application.entity.ZhilianBlacklistEntity;
import com.getjobs.worker.manager.PlaywrightManager;
import com.getjobs.worker.service.CampusPlatformJobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Slf4j
@RestController
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@RequestMapping("/api/campus/{platform}")
public class CampusPlatformController {
    private final CampusPlatformService store;
    private final CampusPlatformJobService jobs;
    private final PlaywrightManager playwright;
    private final ZhilianService zhilianService;

    @GetMapping("/config")
    public Map<String, Object> config(@PathVariable String platform) {
        validate(platform); return store.getConfig(platform);
    }

    @PutMapping("/config")
    public Map<String, Object> saveConfig(@PathVariable String platform, @RequestBody Map<String, Object> body) {
        validate(platform); return store.saveConfig(platform, body);
    }

    /** 实习僧、牛客网与智联招聘共享同一份公司/岗位关键词黑名单。 */
    @GetMapping("/blacklist")
    public List<ZhilianBlacklistEntity> blacklist(@PathVariable String platform) {
        validate(platform);
        return zhilianService.getAllBlacklist();
    }

    @PostMapping("/blacklist")
    public ResponseEntity<?> addBlacklist(@PathVariable String platform,
                                          @RequestBody ZhilianBlacklistEntity blacklist) {
        validate(platform);
        try {
            return ResponseEntity.ok(zhilianService.addBlacklist(blacklist.getType(), blacklist.getValue()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @DeleteMapping("/blacklist/{id}")
    public ResponseEntity<Map<String, Object>> deleteBlacklist(@PathVariable String platform,
                                                                @PathVariable Long id) {
        validate(platform);
        return zhilianService.deleteBlacklist(id)
                ? ResponseEntity.ok(Map.of("success", true))
                : ResponseEntity.status(404).body(Map.of("success", false, "message", "黑名单记录不存在"));
    }

    @GetMapping("/login-status")
    public Map<String, Object> loginStatus(@PathVariable String platform,
                                           @RequestParam(defaultValue = "false") boolean refresh) {
        validate(platform);
        boolean loggedIn = refresh ? playwright.refreshCampusLoginStatus(platform) : playwright.isLoggedIn(platform);
        return Map.of("success", true, "isLoggedIn", loggedIn);
    }

    @PostMapping("/save-cookie")
    public Map<String, Object> saveCookie(@PathVariable String platform) {
        validate(platform);
        playwright.runOnPlaywright(() -> playwright.saveCookiesToDb(platform, "manual save"));
        return Map.of("success", true, "message", "Cookie已保存");
    }

    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> start(@PathVariable String platform) {
        validate(platform);
        if (!playwright.isLoggedIn(platform)) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "请先在浏览器标签页登录该平台"));
        }
        if (jobs.isRunning(platform)) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "投递任务已在运行中"));
        }
        String keywords = String.valueOf(store.getConfig(platform).getOrDefault("keywords", "")).trim();
        if (keywords.isEmpty() || "[]".equals(keywords)) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "请先填写搜索关键词"));
        }
        CompletableFuture.runAsync(() -> {
            try { jobs.execute(platform, message -> log.info("[{}] {}", platform, message)); }
            catch (Exception e) { log.error("[{}] 投递任务失败", platform, e); }
        });
        return ResponseEntity.ok(Map.of("success", true, "message", "投递任务已启动"));
    }

    @PostMapping("/stop")
    public Map<String, Object> stop(@PathVariable String platform) {
        validate(platform); jobs.stop(platform);
        return Map.of("success", true, "message", "已发送停止请求");
    }

    @GetMapping("/status")
    public Map<String, Object> status(@PathVariable String platform) {
        validate(platform); return jobs.status(platform);
    }

    @GetMapping("/stats")
    public Map<String, Object> stats(@PathVariable String platform) {
        validate(platform); return store.stats(platform);
    }

    @GetMapping("/list")
    public Map<String, Object> list(@PathVariable String platform,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        validate(platform);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", store.list(platform, page, size));
        result.put("page", page);
        result.put("size", size);
        result.put("total", store.stats(platform).get("total"));
        return result;
    }

    private void validate(String platform) {
        if (!"shixiseng".equals(platform) && !"nowcoder".equals(platform))
            throw new IllegalArgumentException("不支持的平台: " + platform);
    }
}
