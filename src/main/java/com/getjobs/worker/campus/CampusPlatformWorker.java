package com.getjobs.worker.campus;

import com.getjobs.application.service.CampusPlatformService;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** 实习僧/牛客网的逐岗位搜索与投递器。 */
@Slf4j
@RequiredArgsConstructor
public class CampusPlatformWorker {
    private final String platform;
    private final Page page;
    private final CampusPlatformService store;
    private final BooleanSupplier shouldStop;
    private final Consumer<String> progress;
    private final Set<String> companyBlacklist;
    private final Set<String> jobBlacklist;
    private Page searchPage;

    public int execute(Map<String, Object> config) {
        String raw = String.valueOf(config.getOrDefault("keywords", ""));
        String city = String.valueOf(config.getOrDefault("city", "")).trim();
        String sortOrder = String.valueOf(config.getOrDefault("sortOrder", "latest"));
        int resumeIndex = Math.max(1, intValue(config.get("resumeIndex"), 1));
        List<String> keywords = parseKeywords(raw);
        if (keywords.isEmpty()) throw new IllegalArgumentException("没有配置搜索关键词，请先保存配置");
        int delivered = 0;
        for (int keywordIndex = 0; keywordIndex < keywords.size() && !shouldStop.getAsBoolean(); keywordIndex++) {
            String keyword = keywords.get(keywordIndex);
            String keywordProgress = "关键词 " + (keywordIndex + 1) + "/" + keywords.size() + "【" + keyword + "】";
            progress.accept("开始搜索" + keywordProgress);
            if ("shixiseng".equals(platform)) {
                List<JobCandidate> jobs = searchShixiseng(keyword, city, sortOrder);
                int pageNumber = 1;
                while (!shouldStop.getAsBoolean()) {
                    progress.accept(keywordProgress + "：第 " + pageNumber + " 页发现 " + jobs.size() + " 个岗位");
                    delivered = processJobs(jobs, keywordProgress, pageNumber, resumeIndex, delivered);
                    if (shouldStop.getAsBoolean() || !advanceShixisengPage(jobs)) break;
                    pageNumber++;
                    jobs = collectShixisengJobs(city);
                    if (jobs.isEmpty()) break;
                }
                if (shouldStop.getAsBoolean()) {
                    progress.accept(keywordProgress + "因用户停止而中断，本次累计成功投递 " + delivered + " 个岗位");
                } else {
                    progress.accept(keywordProgress + "全部结果页处理完成，本次累计成功投递 " + delivered + " 个岗位");
                }
            } else {
                List<JobCandidate> jobs = searchNowcoder(keyword, city);
                progress.accept(keywordProgress + "：发现 " + jobs.size() + " 个岗位");
                delivered = processJobs(jobs, keywordProgress, 1, resumeIndex, delivered);
            }
        }
        return delivered;
    }

    private int processJobs(List<JobCandidate> jobs, String keywordProgress, int pageNumber,
                            int resumeIndex, int delivered) {
        for (int i = 0; i < jobs.size() && !shouldStop.getAsBoolean(); i++) {
            JobCandidate job = jobs.get(i);
            String position = keywordProgress + "，第 " + pageNumber + " 页，岗位 " + (i + 1) + "/" + jobs.size();
            if (store.wasDelivered(platform, job.id)) {
                progress.accept(position + "：已投递过，跳过 " + job.title);
                continue;
            }
            String titleMatch = findBlacklistMatch(jobBlacklist, job.title);
            if (titleMatch != null) {
                progress.accept(position + "：黑名单跳过岗位 " + job.title + "（命中：" + titleMatch + "）");
                continue;
            }
            progress.accept(position + "：正在投递 " + job.title);
            store.upsertDiscovered(platform, job.id, job.title, job.company, job.link, job.salary, job.location);
            try {
                DeliveryResult result = deliver(job, resumeIndex);
                store.upsertDiscovered(platform, job.id, result.title, result.company,
                        job.link, result.salary, result.location);
                if (result.skipped) {
                    progress.accept(position + "：" + result.message);
                } else if (result.success) {
                    store.markDelivered(platform, job.id);
                    delivered++;
                    progress.accept(position + "：投递成功 " + result.company + " | " + result.title
                            + "（累计成功 " + delivered + "）");
                } else {
                    store.markFailed(platform, job.id, result.message);
                    progress.accept(position + "：未确认投递成功 " + result.title + "（" + result.message + "）");
                }
            } catch (Exception e) {
                store.markFailed(platform, job.id, e.getMessage());
                log.warn("[{}] 投递失败 {}: {}", platform, job.link, e.getMessage());
                progress.accept(position + "：投递失败，继续下一个 " + job.title);
            }
        }
        return delivered;
    }

    private List<JobCandidate> searchShixiseng(String keyword, String city, String sortOrder) {
        // 必须走网页自身的搜索流程：首页输入关键词后由站点生成完整查询参数。
        page.navigate("https://www.shixiseng.com", new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        if (searchPage != null && searchPage != page && !searchPage.isClosed()) {
            try { searchPage.close(); } catch (Exception ignored) {}
        }
        Locator input = page.locator("input[placeholder*='期望的实习岗位'], input[placeholder*='实习岗位'], .search-input input").first();
        if (!visible(input)) throw new IllegalStateException("未找到实习僧搜索框");
        input.fill(keyword);
        // 首页的真实搜索按钮是 input.form--button，getByText 无法稳定命中 input.value。
        Locator searchButton = page.locator(".search-form input.form--button[value='搜索']").first();
        if (!visible(searchButton)) throw new IllegalStateException("未找到实习僧首页搜索按钮");
        searchPage = openSearchResultPage(input, searchButton);
        searchPage.setDefaultTimeout(12_000);
        progress.accept("已捕获新搜索结果页：" + searchPage.url());
        waitQuietly(searchPage, ".intern-item", 15_000);

        if ("latest".equals(sortOrder)) {
            Locator latest = searchPage.locator(".fast-filter-wrap__item")
                    .filter(new Locator.FilterOptions().setHasText("排序方式"))
                    .getByText("最新", new Locator.GetByTextOptions().setExact(true)).first();
            if (!visible(latest)) throw new IllegalStateException("未找到“最新”排序选项");
            if (!String.valueOf(latest.getAttribute("class")).contains("checked")) {
                latest.click();
                try { searchPage.waitForTimeout(800); } catch (Exception ignored) {}
                waitQuietly(searchPage, ".intern-item", 10_000);
            }
            progress.accept("已切换排序方式：最新");
        }

        return collectShixisengJobs(city);
    }

    private List<JobCandidate> collectShixisengJobs(String city) {
        Locator cards = searchPage.locator(".intern-item");
        Map<String, JobCandidate> unique = new LinkedHashMap<>();
        for (int i = 0; i < Math.min(cards.count(), 60); i++) {
            Locator card = cards.nth(i);
            // 只取岗位标题链接，不点公司、Logo 或整张卡片。
            Locator anchor = card.locator("a.title[href*='/intern/']").first();
            String link = attr(anchor, "href");
            if (link.isBlank()) continue;
            String text = safeText(card);
            String id = attr(card, "data-intern-id");
            if (id.isBlank()) id = tail(link);
            String title = safeText(anchor);
            unique.putIfAbsent(id, new JobCandidate(id, title, "", absolute(link), "", city));
        }
        return new ArrayList<>(unique.values());
    }

    private boolean advanceShixisengPage(List<JobCandidate> currentJobs) {
        Locator next = searchPage.locator(".el-pagination .btn-next").first();
        if (!visible(next)) return false;
        try {
            if (next.isDisabled() || next.getAttribute("disabled") != null
                    || String.valueOf(next.getAttribute("class")).contains("disabled")) return false;
        } catch (Exception ignored) {}

        Set<String> previousIds = currentJobs.stream()
                .map(JobCandidate::id)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        next.click();
        long deadline = System.currentTimeMillis() + 12_000;
        while (System.currentTimeMillis() < deadline && !shouldStop.getAsBoolean()) {
            waitQuietly(searchPage, ".intern-item", 2_000);
            List<JobCandidate> nextJobs = collectShixisengJobs("");
            Set<String> nextIds = nextJobs.stream()
                    .map(JobCandidate::id)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            if (!nextIds.isEmpty() && !nextIds.equals(previousIds)) {
                progress.accept("已进入实习僧下一页：" + searchPage.url());
                return true;
            }
            try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        progress.accept("点击下一页后岗位列表没有变化，停止当前关键词翻页");
        return false;
    }

    private Page openSearchResultPage(Locator input, Locator searchButton) {
        List<Page> before = new ArrayList<>(page.context().pages());
        searchButton.click();
        Page result = findNewSearchPage(before, 8_000);
        if (result == null) {
            input.press("Enter");
            result = findNewSearchPage(before, 8_000);
        }
        if (result == null) {
            throw new IllegalStateException("已点击搜索，但未捕获到新的岗位列表标签页；首页地址=" + page.url());
        }
        try {
            result.waitForURL("**/interns**", new Page.WaitForURLOptions().setTimeout(10_000));
        } catch (Exception e) {
            throw new IllegalStateException("已打开新标签页，但地址不是岗位列表：" + result.url());
        }
        return result;
    }

    private Page findNewSearchPage(List<Page> before, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (page.url().contains("/interns")) return page;
            for (Page candidate : page.context().pages()) {
                if (!before.contains(candidate) && !candidate.isClosed()) return candidate;
            }
            try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
        }
        return null;
    }

    private List<JobCandidate> searchNowcoder(String keyword, String city) {
        page.navigate("https://www.nowcoder.com/jobs/intern/center",
                new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        Locator input = page.locator("input[placeholder*='公司名'], input[placeholder*='职位名'], input[placeholder*='搜索']").first();
        input.fill(keyword);
        input.press("Enter");
        waitQuietly("a[href*='/jobs/detail/']", 15_000);
        Locator links = page.locator("a[href*='/jobs/detail/']");
        Map<String, JobCandidate> unique = new LinkedHashMap<>();
        for (int i = 0; i < Math.min(links.count(), 100); i++) {
            Locator linkNode = links.nth(i);
            String link = attr(linkNode, "href");
            if (link.isBlank()) continue;
            Locator card = linkNode.locator("xpath=ancestor::*[self::li or self::div][1]");
            String text = safeText(card);
            if (!city.isBlank() && !text.contains(city)) continue;
            String id = tail(link);
            String title = safeText(linkNode);
            if (title.isBlank()) title = firstLine(text);
            unique.putIfAbsent(id, new JobCandidate(id, title, "", absolute(link), "", city));
        }
        return new ArrayList<>(unique.values());
    }

    private DeliveryResult deliver(JobCandidate job, int resumeIndex) {
        Page detail = page.context().newPage();
        detail.setDefaultTimeout(12_000);
        try {
            detail.navigate(job.link, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            try { detail.waitForLoadState(LoadState.DOMCONTENTLOADED); } catch (Exception ignored) {}
            String title;
            String company;
            String salary;
            String location;
            Locator apply;
            if ("shixiseng".equals(platform)) {
                title = text(detail, ".new_job_name span", job.title);
                company = text(detail, ".com-name", job.company);
                salary = text(detail, ".job_money", job.salary);
                location = text(detail, ".com_position, .job_position", job.location);
                apply = detail.locator(".resume_apply:visible").first();
            } else {
                title = text(detail, "h1, .job-title, .job-name", job.title);
                company = text(detail, ".company-name, .com-name, [class*='company-name']", job.company);
                salary = text(detail, ".salary, [class*='salary']", job.salary);
                location = text(detail, ".job-address, [class*='address'], [class*='location']", job.location);
                apply = detail.getByText("投递简历", new Page.GetByTextOptions().setExact(true)).first();
                if (!visible(apply)) apply = detail.getByText("立即投递", new Page.GetByTextOptions().setExact(true)).first();
            }
            String companyMatch = findBlacklistMatch(companyBlacklist, company);
            if (companyMatch != null) {
                return new DeliveryResult(false, true, title, company, salary, location,
                        "黑名单跳过公司：" + company + "（命中公司关键词：" + companyMatch + "）");
            }
            String titleMatch = findBlacklistMatch(jobBlacklist, title);
            if (titleMatch != null) {
                return new DeliveryResult(false, true, title, company, salary, location,
                        "黑名单跳过岗位：" + title + "（命中岗位关键词：" + titleMatch + "）");
            }
            if (!visible(apply)) return new DeliveryResult(false, false, title, company, salary, location, "未找到可投递按钮");
            String before = safeText(apply);
            apply.click();
            if ("shixiseng".equals(platform)) selectShixisengResumeAndConfirm(detail, resumeIndex);
            else clickConfirmIfPresent(detail);
            boolean success = waitForSuccess(detail, before);
            closeDialog(detail);
            return new DeliveryResult(success, false, title, company, salary, location,
                    success ? "" : "投递后未检测到成功提示或按钮状态变化");
        } finally {
            try { detail.close(); } catch (Exception ignored) {}
        }
    }

    private void selectShixisengResumeAndConfirm(Page detail, int resumeIndex) {
        try {
            detail.waitForSelector(".common-deliver", new Page.WaitForSelectorOptions().setTimeout(8_000));
        } catch (Exception e) {
            throw new IllegalStateException("点击投个简历后未出现“选择投递简历”弹窗");
        }
        Locator rows = detail.locator(".common-deliver .user-resume");
        List<Integer> attachmentIndexes = new ArrayList<>();
        for (int i = 0; i < rows.count(); i++) {
            if (safeText(rows.nth(i)).contains("附件")) attachmentIndexes.add(i);
        }
        if (attachmentIndexes.isEmpty()) throw new IllegalStateException("投递弹窗中没有可选的附件简历");
        int selected = attachmentIndexes.get(Math.min(resumeIndex - 1, attachmentIndexes.size() - 1));
        Locator radio = rows.nth(selected).locator(".right-item__img img").first();
        if (!visible(radio)) throw new IllegalStateException("找到附件简历，但未找到选择按钮");
        radio.click();
        progress.accept("已选择第 " + resumeIndex + " 份附件简历");
        Locator confirm = detail.locator(".common-deliver__footer .btn")
                .filter(new Locator.FilterOptions().setHasText("确认投递")).first();
        if (!visible(confirm)) throw new IllegalStateException("未找到“确认投递”按钮");
        confirm.click();
    }

    private void clickConfirmIfPresent(Page detail) {
        String[] labels = {"确认投递", "确定", "发送", "使用该简历投递"};
        for (String label : labels) {
            try {
                Locator button = detail.getByText(label, new Page.GetByTextOptions().setExact(true)).last();
                if (visible(button)) { button.click(); break; }
            } catch (Exception ignored) {}
        }
    }

    private boolean waitForSuccess(Page detail, String before) {
        long deadline = System.currentTimeMillis() + 8_000;
        while (System.currentTimeMillis() < deadline && !shouldStop.getAsBoolean()) {
            String body = text(detail, "body", "");
            if (body.contains("投递成功") || body.contains("已投递") || body.contains("简历已发送")
                    || body.contains("申请成功") || body.contains("继续沟通") || body.contains("已申请")) return true;
            try {
                Locator action = "shixiseng".equals(platform)
                        ? detail.locator(".resume_apply:visible").first()
                        : detail.getByText("投递简历", new Page.GetByTextOptions().setExact(true)).first();
                String after = visible(action) ? safeText(action) : "";
                if (!after.isBlank() && !before.equals(after)
                        && (after.contains("已投递") || after.contains("已申请") || after.contains("继续沟通"))) return true;
            } catch (Exception ignored) {}
            try { Thread.sleep(250); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        return false;
    }

    private void closeDialog(Page detail) {
        try {
            Locator close = detail.locator(".el-dialog__wrapper:visible .el-dialog__close, .modal:visible [aria-label='Close'], .close:visible").first();
            if (visible(close)) close.click();
        } catch (Exception ignored) {}
    }

    private void waitQuietly(String selector, double timeout) {
        waitQuietly(page, selector, timeout);
    }

    private void waitQuietly(Page target, String selector, double timeout) {
        try { target.waitForSelector(selector, new Page.WaitForSelectorOptions().setTimeout(timeout)); }
        catch (Exception e) { log.warn("[{}] 等待岗位列表超时，当前地址={}", platform, target.url()); }
    }

    private boolean visible(Locator locator) {
        try { return locator != null && locator.count() > 0 && locator.isVisible(); } catch (Exception e) { return false; }
    }
    private String text(Page p, String selector, String fallback) {
        try { Locator l = p.locator(selector).first(); return visible(l) ? safeText(l) : fallback; }
        catch (Exception e) { return fallback; }
    }
    private String safeText(Locator locator) { try { return locator.innerText().trim(); } catch (Exception e) { return ""; } }
    private String attr(Locator locator, String name) { try { String v = locator.getAttribute(name); return v == null ? "" : v; } catch (Exception e) { return ""; } }
    private String absolute(String link) {
        if (link.startsWith("http")) return link;
        return ("shixiseng".equals(platform) ? "https://www.shixiseng.com" : "https://www.nowcoder.com") + link;
    }
    private String tail(String link) { String s = link.split("[?#]", 2)[0]; return s.substring(s.lastIndexOf('/') + 1); }
    private String firstLine(String text) { return text == null || text.isBlank() ? "" : text.split("\\R", 2)[0].trim(); }
    private String encode(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
    private int intValue(Object value, int fallback) { try { return Integer.parseInt(String.valueOf(value)); } catch (Exception e) { return fallback; } }
    private List<String> parseKeywords(String raw) {
        String normalized = raw == null ? "" : raw.replace('，', ',').replace("[", "").replace("]", "").replace("\"", "");
        List<String> values = new ArrayList<>();
        for (String part : normalized.split(",")) if (!part.trim().isEmpty()) values.add(part.trim());
        return values;
    }

    static String findBlacklistMatch(Set<String> blacklist, String target) {
        if (blacklist == null || blacklist.isEmpty() || target == null || target.isBlank()) return null;
        String normalizedTarget = target.trim().toLowerCase(java.util.Locale.ROOT);
        return blacklist.stream()
                .filter(java.util.Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .filter(value -> normalizedTarget.contains(value.toLowerCase(java.util.Locale.ROOT)))
                .findFirst()
                .orElse(null);
    }

    private record JobCandidate(String id, String title, String company, String link, String salary, String location) {}
    private record DeliveryResult(boolean success, boolean skipped, String title, String company, String salary, String location, String message) {}
}
