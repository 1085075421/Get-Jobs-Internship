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
        if (keywords.isEmpty()) {
            if (!"nowcoder".equals(platform)) {
                throw new IllegalArgumentException("没有配置搜索关键词，请先保存配置");
            }
            String currentPageProgress = "当前页面已选岗位";
            progress.accept("牛客网搜索关键词为空，保留当前岗位列表及手动筛选条件直接投递");
            List<JobCandidate> jobs = collectCurrentNowcoderJobs();
            int delivered = processNowcoderPages(jobs, currentPageProgress, "", resumeIndex, 0);
            if (shouldStop.getAsBoolean()) {
                progress.accept(currentPageProgress + "因用户停止而中断，本次累计成功投递 " + delivered + " 个岗位");
            } else {
                progress.accept(currentPageProgress + "处理完成，本次累计成功投递 " + delivered + " 个岗位");
            }
            return delivered;
        }
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
                delivered = processNowcoderPages(jobs, keywordProgress, city, resumeIndex, delivered);
            }
        }
        return delivered;
    }

    private int processNowcoderPages(List<JobCandidate> initialJobs, String keywordProgress, String city,
                                     int resumeIndex, int delivered) {
        List<JobCandidate> jobs = initialJobs;
        int pageNumber = 1;
        while (!shouldStop.getAsBoolean() && !jobs.isEmpty()) {
            progress.accept(keywordProgress + "：第 " + pageNumber + " 页发现 " + jobs.size() + " 个岗位");
            delivered = processJobs(jobs, keywordProgress, pageNumber, resumeIndex, delivered);
            if (shouldStop.getAsBoolean() || !advanceNowcoderPage(jobs)) break;
            pageNumber++;
            jobs = collectNowcoderJobs(city);
        }
        if (!shouldStop.getAsBoolean()) {
            progress.accept(keywordProgress + "全部结果页处理完成，本次累计成功投递 " + delivered + " 个岗位");
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
        return collectNowcoderJobs(city);
    }

    /**
     * 关键词为空时复用用户已经筛选好的牛客岗位结果页，不执行导航、搜索或城市二次过滤。
     */
    private List<JobCandidate> collectCurrentNowcoderJobs() {
        int initialCount = validateCurrentNowcoderJobList();
        progress.accept("已识别牛客网当前岗位列表（已加载 " + initialCount + " 个），正在继续下拉加载全部岗位");
        loadAllNowcoderJobs();
        List<JobCandidate> jobs = collectNowcoderJobs("");
        if (jobs.isEmpty()) {
            throw new IllegalStateException("牛客网当前岗位列表未提取到可投递岗位，请等待列表加载完成后重试");
        }
        return jobs;
    }

    int validateCurrentNowcoderJobList() {
        String currentUrl = safePageUrl();
        if (!isNowcoderJobListUrl(currentUrl)) {
            throw new IllegalStateException(
                    "搜索关键词为空，但当前标签页不是牛客网岗位列表页。请先在牛客网设置筛选条件并确认岗位已显示。当前页面: "
                            + currentUrl);
        }
        waitQuietly("a[href*='/jobs/detail/']", 15_000);
        int count = page.locator("a[href*='/jobs/detail/']").count();
        if (count <= 0) {
            throw new IllegalStateException(
                    "搜索关键词为空，且当前牛客网结果页没有检测到岗位。请等待岗位列表加载完成后重试。当前页面: "
                            + currentUrl);
        }
        return count;
    }

    static boolean isNowcoderJobListUrl(String url) {
        if (url == null) return false;
        String normalized = url.toLowerCase(java.util.Locale.ROOT);
        return normalized.startsWith("https://www.nowcoder.com/jobs/")
                || normalized.startsWith("http://www.nowcoder.com/jobs/")
                || normalized.startsWith("https://nowcoder.com/jobs/")
                || normalized.startsWith("http://nowcoder.com/jobs/");
    }

    private void loadAllNowcoderJobs() {
        int lastCount = -1;
        int stableRounds = 0;
        for (int round = 0; round < 100 && !shouldStop.getAsBoolean(); round++) {
            int currentCount = page.locator("a[href*='/jobs/detail/']").count();
            if (currentCount == lastCount) stableRounds++;
            else stableRounds = 0;
            lastCount = currentCount;
            if (stableRounds >= 5) {
                log.info("[nowcoder] 连续{}轮没有新增岗位，当前页面共{}个岗位链接", stableRounds, currentCount);
                break;
            }
            page.evaluate("() => window.scrollTo(0, document.body.scrollHeight)");
            try { page.waitForTimeout(600); } catch (Exception ignored) {}
        }
    }

    /**
     * 牛客使用数字分页。优先点击当前页的下一个数字，并等待岗位 ID 集合发生变化。
     * 通过分页容器和 active/current 状态定位，避免误点正文里的普通数字。
     */
    private boolean advanceNowcoderPage(List<JobCandidate> currentJobs) {
        Set<String> previousIds = currentJobs.stream()
                .map(JobCandidate::id)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Object clicked = page.evaluate("""
                () => {
                  const visible = el => {
                    const r = el.getBoundingClientRect();
                    const s = getComputedStyle(el);
                    return r.width > 0 && r.height > 0 && s.display !== 'none' && s.visibility !== 'hidden';
                  };
                  const numericControls = root => Array.from(root.querySelectorAll(
                      'button,a,li,span,[role="button"],[class*="page-item"],[class*="pager-item"]'))
                    .filter(visible)
                    .filter(el => /^\\d+$/.test((el.textContent || '').trim()));
                  const roots = Array.from(document.querySelectorAll('nav,ul,div'))
                    .filter(visible)
                    .map(root => ({root, controls: numericControls(root)}))
                    .filter(item => new Set(item.controls.map(el => (el.textContent || '').trim())).size >= 2)
                    .filter(item => item.root.getBoundingClientRect().top > window.innerHeight * 0.45)
                    .sort((a, b) => {
                      const ar = a.root.getBoundingClientRect();
                      const br = b.root.getBoundingClientRect();
                      return (ar.width * ar.height) - (br.width * br.height);
                    });
                  if (!roots.length) return false;
                  const {root, controls} = roots[0];
                  const active = controls.find(el => el.getAttribute('aria-current') === 'page'
                    || /active|current|selected|checked/i.test(el.className || '')
                    || /active|current|selected|checked/i.test(el.parentElement?.className || ''));
                  let current = active ? Number((active.textContent || '').trim()) : NaN;
                  if (!Number.isFinite(current)) {
                    const u = new URL(location.href);
                    current = Number(u.searchParams.get('page') || u.searchParams.get('pageNum')
                      || u.searchParams.get('current') || '1');
                  }
                  const next = controls.find(el => Number((el.textContent || '').trim()) === current + 1);
                  if (!next) return false;
                  const target = next.closest('button,a,li,[role="button"],[class*="page-item"],[class*="pager-item"]')
                    || next.querySelector('button,a,[role="button"]') || next;
                  if (target.hasAttribute('disabled') || /disabled/i.test(target.className || '')) return false;
                  target.scrollIntoView({block: 'center'});
                  target.click();
                  return true;
                }
                """);
        if (!Boolean.TRUE.equals(clicked)) return false;

        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline && !shouldStop.getAsBoolean()) {
            try { page.waitForTimeout(300); } catch (Exception ignored) {}
            List<JobCandidate> nextJobs = collectNowcoderJobs("");
            Set<String> nextIds = nextJobs.stream()
                    .map(JobCandidate::id)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            if (!nextIds.isEmpty() && !nextIds.equals(previousIds)) {
                progress.accept("已进入牛客网下一页：" + safePageUrl());
                return true;
            }
        }
        progress.accept("点击牛客网下一页后岗位列表没有变化，停止翻页");
        return false;
    }

    private List<JobCandidate> collectNowcoderJobs(String city) {
        Locator links = page.locator("a[href*='/jobs/detail/']");
        Map<String, JobCandidate> unique = new LinkedHashMap<>();
        for (int i = 0; i < links.count(); i++) {
            Locator linkNode = links.nth(i);
            String link = attr(linkNode, "href");
            if (link.isBlank()) continue;
            Locator card = linkNode.locator("xpath=ancestor::*[self::li or self::div][1]");
            String text = safeText(card);
            if (!city.isBlank() && !text.contains(city)) continue;
            String id = tail(link);
            String title = firstLine(safeText(linkNode));
            if (title.isBlank()) title = firstLine(text);
            unique.putIfAbsent(id, new JobCandidate(id, title, "", absolute(link), "", city));
        }
        return new ArrayList<>(unique.values());
    }

    private String safePageUrl() {
        try { return page.url(); } catch (Exception e) { return "(取不到URL)"; }
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
                apply = findNowcoderApplyButton(detail);
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
            if (!visible(apply)) {
                String body = text(detail, "body", "");
                if (body.contains("已申请") || body.contains("已投递")) {
                    return new DeliveryResult(false, true, title, company, salary, location, "该岗位已经投递过，跳过");
                }
                return new DeliveryResult(false, false, title, company, salary, location,
                        "未找到“立即申请/投递简历/立即投递”按钮");
            }
            String before = safeText(apply);
            apply.click();
            if ("shixiseng".equals(platform)) selectShixisengResumeAndConfirm(detail, resumeIndex);
            else if (!clickConfirmIfPresent(detail)) {
                return new DeliveryResult(false, false, title, company, salary, location,
                        "已打开选择简历弹窗，但未能点击弹窗中的“投递简历”按钮");
            }
            boolean success = waitForSuccess(detail, before);
            closeDialog(detail);
            return new DeliveryResult(success, false, title, company, salary, location,
                    success ? "" : "投递后未检测到成功提示或按钮状态变化");
        } finally {
            try { detail.close(); } catch (Exception ignored) {}
        }
    }

    private Locator findNowcoderApplyButton(Page detail) {
        String[] labels = {"立即申请", "投递简历", "立即投递"};
        for (int attempt = 0; attempt < 10; attempt++) {
            for (String label : labels) {
                try {
                    Locator candidate = detail.getByText(label, new Page.GetByTextOptions().setExact(true)).first();
                    if (visible(candidate)) return candidate;
                } catch (Exception ignored) {}
            }
            try { detail.waitForTimeout(200); } catch (Exception ignored) {}
        }
        return detail.getByText("立即申请", new Page.GetByTextOptions().setExact(true)).first();
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

    private boolean clickConfirmIfPresent(Page detail) {
        // 牛客点击“立即申请”后会先弹出选择简历窗口，必须再点击弹窗底部的
        // “投递简历”才会真正提交；只点击第一步不能算投递成功。
        String[] labels = {"投递简历", "确认投递", "确定", "发送", "使用该简历投递"};
        String clickableSelector = "button:visible, a:visible, [role='button']:visible";
        for (int attempt = 0; attempt < 30; attempt++) {
            for (String label : labels) {
                try {
                    Locator candidates = detail.locator(clickableSelector)
                            .filter(new Locator.FilterOptions().setHasText(label));
                    for (int i = candidates.count() - 1; i >= 0; i--) {
                        Locator button = candidates.nth(i);
                        if (!visible(button) || !safeText(button).contains(label)) continue;
                        progress.accept("牛客投递弹窗已出现，正在点击“" + label + "”");
                        try {
                            button.scrollIntoViewIfNeeded();
                            button.click(new Locator.ClickOptions().setTimeout(3_000));
                        } catch (Exception clickError) {
                            log.warn("[nowcoder] 普通点击“{}”失败，尝试强制点击：{}", label, clickError.getMessage());
                            button.click(new Locator.ClickOptions().setForce(true).setTimeout(3_000));
                        }
                        log.info("[nowcoder] 已点击投递弹窗按钮“{}”", label);
                        progress.accept("已点击牛客投递弹窗中的“" + label + "”");
                        return true;
                    }
                } catch (Exception ignored) {}
            }
            try { detail.waitForTimeout(250); } catch (Exception ignored) {}
        }

        // 最后使用 DOM 点击兜底：部分牛客弹窗把文字放在多层 span/div 中，
        // Playwright 的文字定位可能只命中文本节点而不是实际绑定事件的父按钮。
        try {
            Object clicked = detail.evaluate("""
                    labels => {
                      const visible = el => {
                        const rect = el.getBoundingClientRect();
                        const style = getComputedStyle(el);
                        return rect.width > 0 && rect.height > 0
                          && style.display !== 'none' && style.visibility !== 'hidden';
                      };
                      const values = Array.from(labels);
                      const nodes = Array.from(document.querySelectorAll('button,a,[role="button"],span,div'))
                        .filter(visible)
                        .filter(el => values.some(label => (el.textContent || '').trim() === label));
                      const node = nodes.find(el => /^(BUTTON|A)$/.test(el.tagName)
                        || el.getAttribute('role') === 'button') || nodes[nodes.length - 1];
                      if (!node) return false;
                      const target = node.closest('button,a,[role="button"]') || node;
                      target.scrollIntoView({block: 'center'});
                      target.click();
                      return true;
                    }
                    """, java.util.List.of(labels));
            if (Boolean.TRUE.equals(clicked)) {
                log.info("[nowcoder] 已通过DOM兜底点击投递弹窗按钮");
                progress.accept("已通过DOM兜底点击牛客投递弹窗中的“投递简历”");
                return true;
            }
        } catch (Exception e) {
            log.warn("[nowcoder] DOM兜底点击投递弹窗失败：{}", e.getMessage());
        }
        try {
            Object visibleActions = detail.evaluate("""
                    () => Array.from(document.querySelectorAll('button,a,[role="button"]'))
                        .filter(el => {
                            const rect = el.getBoundingClientRect();
                            const style = getComputedStyle(el);
                            return rect.width > 0 && rect.height > 0 && style.visibility !== 'hidden' && style.display !== 'none';
                        })
                        .map(el => ({ tag: el.tagName, text: (el.innerText || el.textContent || '').trim(), className: el.className }))
                        .filter(item => item.text)
                        .slice(-30)
                    """);
            log.warn("[nowcoder] 已等待投递弹窗，但未找到可点击的投递确认按钮，页面={}，可见操作元素={}",
                    detail.url(), visibleActions);
        } catch (Exception e) {
            log.warn("[nowcoder] 已等待投递弹窗，但未找到可点击的投递确认按钮，页面={}，读取可见按钮失败={}",
                    detail.url(), e.getMessage());
        }
        return false;
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
                        : findNowcoderApplyButton(detail);
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
