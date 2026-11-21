package com.getjobs.worker.service;

import com.getjobs.application.service.CampusPlatformService;
import com.getjobs.application.service.ZhilianService;
import com.getjobs.worker.campus.CampusPlatformWorker;
import com.getjobs.worker.manager.PlaywrightManager;
import com.microsoft.playwright.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Slf4j
@Service
@RequiredArgsConstructor
public class CampusPlatformJobService {
    private final PlaywrightManager playwrightManager;
    private final CampusPlatformService store;
    private final ZhilianService zhilianService;
    private final Map<String, Boolean> running = new ConcurrentHashMap<>();
    private final Map<String, Boolean> stopping = new ConcurrentHashMap<>();

    public int execute(String platform, Consumer<String> progress) {
        validate(platform);
        if (isRunning(platform)) throw new IllegalStateException("任务已在运行中");
        running.put(platform, true);
        stopping.put(platform, false);
        playwrightManager.pauseCampusMonitoring(platform);
        try {
            playwrightManager.ensureReady();
            Page page = page(platform);
            if (page == null) throw new IllegalStateException("平台页面未初始化");
            CampusPlatformWorker worker = new CampusPlatformWorker(platform, page, store,
                    () -> stopping.getOrDefault(platform, false), progress,
                    zhilianService.getBlacklistValues("company"),
                    zhilianService.getBlacklistValues("job"));
            int count = playwrightManager.callOnPlaywright(() -> worker.execute(store.getConfig(platform)));
            if (count > 0) {
                // 能完成真实投递说明当前会话有效，避免首页瞬时状态将 UI 误判为未登录。
                playwrightManager.setLoginStatus(platform, true);
            }
            if (stopping.getOrDefault(platform, false)) {
                progress.accept("投递任务已按用户请求停止，共成功投递 " + count + " 个岗位");
            } else {
                progress.accept("所有关键词的全部结果页处理完成，共成功投递 " + count + " 个岗位");
            }
            return count;
        } finally {
            running.put(platform, false);
            stopping.put(platform, false);
            playwrightManager.resumeCampusMonitoring(platform);
        }
    }

    public void stop(String platform) { validate(platform); stopping.put(platform, true); }
    public boolean isRunning(String platform) { return running.getOrDefault(platform, false); }
    public Map<String, Object> status(String platform) {
        return Map.of("platform", platform, "isRunning", isRunning(platform),
                "isLoggedIn", playwrightManager.isLoggedIn(platform));
    }

    private Page page(String platform) {
        return "shixiseng".equals(platform) ? playwrightManager.getShixisengPage() : playwrightManager.getNowcoderPage();
    }
    private void validate(String platform) {
        if (!"shixiseng".equals(platform) && !"nowcoder".equals(platform))
            throw new IllegalArgumentException("不支持的平台: " + platform);
    }
}
