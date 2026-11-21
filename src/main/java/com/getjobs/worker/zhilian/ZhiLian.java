package com.getjobs.worker.zhilian;

import com.getjobs.application.entity.ZhilianJobDataEntity;
import com.getjobs.application.service.ZhilianService;
import com.getjobs.worker.utils.Job;
import com.getjobs.worker.utils.JobUtils;
import com.getjobs.worker.utils.PlaywrightUtil;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/**
 * @author loks666
 * 项目链接: <a href="https://github.com/loks666/get_jobs">https://github.com/loks666/get_jobs</a>
 * 智联招聘自动投递 - Playwright版本
 */
@Slf4j
@Component
@Scope("prototype")
@RequiredArgsConstructor
public class ZhiLian {

    @Setter
    private Page page;

    @Setter
    private ZhilianConfig config;

    @Setter
    private ProgressCallback progressCallback;

    @Setter
    private Supplier<Boolean> shouldStopCallback;

    private final List<Job> resultList = new ArrayList<>();
    private boolean isLimit = false;
    private int maxPage = 500;
    private Set<String> blackCompanies = Set.of();
    private Set<String> blackJobs = Set.of();
    private final Set<String> processedJobIds = new HashSet<>();

    private static final String HOME_URL = "https://www.zhaopin.com/sou/";
    /** 智联新版 /jobs/ 与旧版 /sou/ 的职位卡片，按新版优先。 */
    private static final String JOB_CARD_SELECTOR = "div.job-card, div.joblist-box__item";

    private final ZhilianService zhilianService;

    private static class PageJob {
        int index;
        String jobId;
        String jobTitle;
        String companyName;
        String jobLink;
        String salary;
        String location;
        String experience;
        String degree;
        boolean alreadyApplied;

        PageJob(int index, String jobId, String jobTitle, String companyName,
                String jobLink, String salary, String location, String experience,
                String degree, boolean alreadyApplied) {
            this.index = index;
            this.jobId = jobId;
            this.jobTitle = jobTitle;
            this.companyName = companyName;
            this.jobLink = jobLink;
            this.salary = salary;
            this.location = location;
            this.experience = experience;
            this.degree = degree;
            this.alreadyApplied = alreadyApplied;
        }
    }

    /**
     * 进度回调接口
     */
    @FunctionalInterface
    public interface ProgressCallback {
        void accept(String message, Integer current, Integer total);
    }

    /**
     * 准备工作：加载配置、初始化数据
     */
    public void prepare() {
        log.info("智联招聘准备工作开始...");
        resultList.clear();
        processedJobIds.clear();
        isLimit = false;
        blackCompanies = normalizeBlacklist(zhilianService.getBlacklistValues("company"));
        blackJobs = normalizeBlacklist(zhilianService.getBlacklistValues("job"));
        log.info("智联黑名单加载完成：公司 {} 条，岗位 {} 条", blackCompanies.size(), blackJobs.size());
        log.info("智联招聘准备工作完成");
    }

    /**
     * 执行投递任务
     * @return 投递数量
     */
    public int execute() {
        log.info("智联招聘投递任务开始...");
        long startTime = System.currentTimeMillis();

        try {
            List<String> cityCodes = config.getCityCodes();
            if (cityCodes == null || cityCodes.isEmpty()) {
                cityCodes = List.of(config.getCityCode());
            }
            for (String cityCode : cityCodes) {
                String cityName = zhilianService.getNameByTypeAndCode("city", cityCode);
                String displayCity = cityName == null ? cityCode : cityName;
                log.info("开始处理智联城市：{}（{}）", displayCity, cityCode);
                sendProgress("正在处理城市：" + displayCity, null, null);

                for (String keyword : config.getKeywords()) {
                    if (shouldStop() || isLimit) {
                        sendProgress("用户取消投递或已达上限", null, null);
                        break;
                    }
                    deliverByKeyword(keyword, buildBaseUrl(cityCode, 1), displayCity);
                }
                if (shouldStop() || isLimit) break;
                log.info("城市【{}】全部关键词投递完成", displayCity);
            }

            long duration = System.currentTimeMillis() - startTime;
            String message = String.format("智联招聘投递完成，共投递%d个岗位，用时%s",
                resultList.size(), formatDuration(duration));
            log.info(message);
            sendProgress(message, null, null);

            if (!resultList.isEmpty()) {
                log.info("新投递公司如下:");
                resultList.forEach(job -> log.info(job.toString()));
            } else {
                log.info("未投递新的岗位...");
            }

        } catch (Exception e) {
            log.error("智联招聘投递过程出现异常", e);
            sendProgress("投递出现异常: " + e.getMessage(), null, null);
        }

        return resultList.size();
    }

    /**
     * 按关键词投递
     */
    private void deliverByKeyword(String keyword, String baseUrl, String cityName) {
        if (isLimit) {
            return;
        }

        try {
            log.info("开始投递城市【{}】关键词【{}】", cityName, keyword);
            sendProgress("正在搜索：" + cityName + " / " + keyword, null, null);

            // 导航到搜索页面（路径参数：jl+城市码 + p1 + sl）
            page.navigate(baseUrl);
            PlaywrightUtil.sleep(2);

            // 在搜索框输入关键词并触发搜索（Enter键更稳健）
            try {
                Locator keywordInput = findKeywordInput();
                if (keywordInput == null || keywordInput.count() == 0) {
                    log.warn("未找到搜索输入框，跳过关键词: {}", keyword);
                    return;
                }
                keywordInput.fill("");
                keywordInput.fill(keyword);
                try { keywordInput.press("Enter"); } catch (Exception ignored) {}
                PlaywrightUtil.sleep(2);
            } catch (Exception e) {
                log.warn("搜索框输入关键词失败，跳过当前关键词: {}", e.getMessage());
                return;
            }

            // 等待岗位列表加载。智联新版 /jobs/ 已将 joblist-box__item 改为 job-card。
            try {
                page.waitForSelector(JOB_CARD_SELECTOR,
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
            } catch (Exception e) {
                log.warn("等待岗位列表超时，跳过当前关键词；当前地址={}", page.url());
                return;
            }

            // 智联在搜索框回车后会重新生成 URL，初始 URL 上的工作性质参数会被覆盖。
            // 因此必须先完成关键词搜索，再基于当前结果 URL 应用工作性质筛选。
            if (!applyJobNatureAfterKeywordSearch()) {
                log.warn("应用工作性质筛选失败，跳过当前关键词: {}", keyword);
                return;
            }

            // 遍历所有页面：仅以“下一页”按钮禁用状态为主，最多50页
            int pageNum = 1;
            while (pageNum <= 50) {
                if (shouldStop() || isLimit) {
                    sendProgress("用户取消投递或已达上限", null, null);
                    return;
                }

                log.info("开始投递【{}】关键词，第【{}】页...", keyword, pageNum);
                sendProgress(String.format("正在投递第%d页", pageNum), pageNum, 50);

                // 等待岗位列表出现（CSS选择器）
                try {
                    page.waitForSelector(JOB_CARD_SELECTOR,
                        new Page.WaitForSelectorOptions().setTimeout(10_000));
                } catch (Exception e) {
                    log.warn("等待岗位列表失败，刷新页面重试");
                    page.reload();
                    PlaywrightUtil.sleep(1);
                }

                // 投递当前页面
                if (!deliverCurrentPage(keyword)) {
                    break;
                }

                PlaywrightUtil.sleep(2);

                // 判断是否还有下一页
                if (isNextDisabled()) {
                    log.info("下一页按钮不可点击，结束翻页");
                    break;
                }

                // 点击下一页
                Locator nextBtn = page.locator("a.soupager__btn:has-text(\"下一页\")");
                if (nextBtn.count() > 0) {
                    try { nextBtn.first().scrollIntoViewIfNeeded(); } catch (Exception ignored) {}
                    nextBtn.first().click();
                    PlaywrightUtil.sleep(2);
                    pageNum++;
                } else {
                    log.info("未找到下一页按钮，结束翻页");
                    break;
                }
            }

            log.info("关键词【{}】投递完成", keyword);
        } catch (Exception e) {
            log.error("投递关键词【{}】时出现异常", keyword, e);
        }
    }

    /**
     * 投递当前页面的所有职位
     * @return 是否继续投递下一页
     */
    private boolean deliverCurrentPage(String keyword) {
        try {
            page.waitForSelector(JOB_CARD_SELECTOR,
                    new Page.WaitForSelectorOptions().setTimeout(15000));

            if (checkIsLimit()) {
                sendProgress("用户取消投递或已达上限", null, null);
                return false;
            }

            Locator cards = page.locator(JOB_CARD_SELECTOR);
            int count = cards.count();
            log.info("检测到当前页岗位数量: {}", count);

            List<PageJob> jobs = collectPageJobsFromState();
            if (jobs.isEmpty()) {
                log.error("智联页面未暴露 positionList，无法取得真实岗位元数据，本页停止投递");
                return false;
            }
            if (jobs.size() != count) {
                log.warn("岗位状态数据数量({})与页面卡片数量({})不一致，仅处理两者共有部分", jobs.size(), count);
            }

            int deliverableCount = Math.min(jobs.size(), count);
            String searchUrl = page.url();
            for (int jobIndex = 0; jobIndex < deliverableCount; jobIndex++) {
                PageJob pj = jobs.get(jobIndex);
                if (shouldStop()) {
                    sendProgress("用户取消投递或已达上限", null, null);
                    return false;
                }

                // 某些成功弹窗会延迟出现；每次点击下一张卡片前再清理一次遮罩。
                if (!closeDeliverySuccessDialog()) {
                    log.warn("投递成功弹窗未关闭，停止当前页，避免遮罩导致误点击");
                    return false;
                }

                if (pj.alreadyApplied) {
                    log.info("岗位已投递，跳过：{} | {}", pj.companyName, pj.jobTitle);
                    continue;
                }

                if (!processedJobIds.add(pj.jobId)) {
                    log.info("本轮任务已处理相同岗位，跳过：{} | {} | jobId={}",
                            pj.companyName, pj.jobTitle, pj.jobId);
                    continue;
                }

                saveJobIfAbsent(pj);
                String companyMatch = findBlacklistMatch(blackCompanies, pj.companyName);
                if (companyMatch != null) {
                    log.info("被过滤：智联公司黑名单命中 | 公司：{} | 岗位：{} | 关键词：{}",
                            pj.companyName, pj.jobTitle, companyMatch);
                    continue;
                }
                String jobMatch = findBlacklistMatch(blackJobs, pj.jobTitle);
                if (jobMatch != null) {
                    log.info("被过滤：智联岗位黑名单命中 | 公司：{} | 岗位：{} | 关键词：{}",
                            pj.companyName, pj.jobTitle, jobMatch);
                    continue;
                }
                log.info("正在投递 {}/{}：{} | {} | jobId={}",
                        jobIndex + 1, deliverableCount, pj.companyName, pj.jobTitle, pj.jobId);
                sendProgress("正在投递：" + pj.jobTitle, jobIndex + 1, deliverableCount);

                Locator card = page.locator(JOB_CARD_SELECTOR).nth(pj.index);
                Locator applyBtn;
                if (isNewJobCard(card)) {
                    // 新版是左侧卡片 + 右侧详情布局，投递按钮不在卡片内部。
                    card.click();
                    try {
                        page.waitForSelector("button.job-detail-summary__apply",
                                new Page.WaitForSelectorOptions().setTimeout(5000));
                    } catch (Exception ignored) {
                    }
                    applyBtn = page.locator("button.job-detail-summary__apply").first();
                } else {
                    applyBtn = card.locator("button.collect-and-apply__btn").first();
                }
                if (applyBtn.count() == 0) {
                    log.info("岗位【{}】未找到立即投递按钮，跳过", pj.jobTitle);
                    continue;
                }
                try {
                    // 点击前：注册监听器，统一关闭由当前页面打开的新窗口（弹出页）
                    java.util.function.Consumer<Page> closer = (Page newPage) -> {
                        try {
                            // 只关闭由当前 page 打开的子窗口，避免误伤
                            if (newPage.opener() == page) {
                                try { newPage.waitForLoadState(); } catch (Exception ignored) {}
                                try { newPage.waitForTimeout(200); } catch (Exception ignored) {}
                                try { newPage.close(); } catch (Exception ignored) {}
                            }
                        } catch (Exception ignored) {}
                    };
                    page.context().onPage(closer);

                    // 仅通过监听器捕捉并关闭由当前页打开的新窗口，避免与 waitForPopup 产生竞态
                    try {
                        applyBtn.click(); /* 点击投递按钮（关键定位注释：delivery-click-line）*/
                    } finally {
                        // 取消监听，避免影响后续流程
                        try { page.context().offPage(closer); } catch (Exception ignored) {}
                    }

                    try {
                        zhilianService.markDeliveredByJobId(pj.jobId);
                    } catch (Exception databaseError) {
                        log.warn("岗位已投递，但数据库状态更新失败：jobId={}，原因={}",
                                pj.jobId, databaseError.getMessage());
                    }
                    Job delivered = new Job();
                    delivered.setHref(pj.jobLink);
                    delivered.setJobName(pj.jobTitle);
                    delivered.setCompanyName(pj.companyName);
                    delivered.setJobArea(pj.location);
                    delivered.setSalary(pj.salary);
                    delivered.setJobInfo(pj.experience + "·" + pj.degree);
                    delivered.setCompanyTag(pj.degree);
                    delivered.setRecruiter("");
                    resultList.add(delivered);
                    log.info("已完成立即投递点击，继续下一个岗位：jobId={}，title={}，company={}",
                            pj.jobId, pj.jobTitle, pj.companyName);

                    if (!closeDeliverySuccessDialog()) {
                        log.warn("岗位已投递成功，但成功弹窗未能关闭，为避免遮挡后续操作，停止当前页投递");
                        return false;
                    }

                    if (!page.url().contains("zhaopin.com/jobs/")) {
                        page.navigate(searchUrl);
                        page.waitForSelector(JOB_CARD_SELECTOR,
                                new Page.WaitForSelectorOptions().setTimeout(10_000));
                    }
                } catch (Exception clickEx) {
                    log.warn("投递失败，继续下一个岗位: {}", clickEx.getMessage());
                }

                if (checkIsLimit()) {
                    sendProgress("用户取消投递或已达上限", null, null);
                    return false;
                }
            }

            return true;
        } catch (Exception e) {
            log.error("投递当前页面失败", e);
            try {
                saveCurrentPageHtml();
                log.info("已保存当前页面到 src/main/java/com/getjobs/worker/zhilian/page.html 以便排查");
            } catch (Exception saveEx) {
                log.warn("保存当前页面HTML失败: {}", saveEx.getMessage());
            }
            return false;
        }
    }

    /**
     * 处理投递弹窗
     */
    private void handleDeliveryDialog() {
        try {
            // 获取所有页面
            List<Page> pages = page.context().pages();
            if (pages.size() < 2) {
                log.warn("未检测到投递弹窗页面");
                return;
            }

            // 切换到最新打开的页面（投递弹窗）
            Page dialogPage = pages.get(pages.size() - 1);

            try {
                // 检查投递结果（CSS选择器）
                Locator deliverResult = dialogPage.locator("div.deliver-dialog");
                if (deliverResult.count() > 0) {
                    String text = deliverResult.textContent();
                    if (text != null && text.contains("申请成功")) {
                        log.info("岗位申请成功！");
                    }
                }
            } catch (Exception e) {
                log.debug("读取投递结果失败: {}", e.getMessage());
            }

            // 关闭弹窗
            try {
                Locator closeButton = dialogPage.locator("img[title='close-icon']");
                if (closeButton.count() > 0) {
                    closeButton.click();
                    PlaywrightUtil.sleep(1);
                }
            } catch (Exception e) {
                log.debug("关闭投递弹窗失败: {}", e.getMessage());
                if (checkIsLimit()) {
                    return;
                }
            }

            // 投递相似职位
            deliverSimilarJobs(dialogPage);

            // 关闭弹窗页面
            try {
                dialogPage.close();
            } catch (Exception e) {
                log.debug("关闭弹窗页面失败: {}", e.getMessage());
            }

        } catch (Exception e) {
            log.error("处理投递弹窗失败", e);
        }
    }

    /**
     * 投递相似职位
     */
    private void deliverSimilarJobs(Page dialogPage) {
        try {
            // 全选相似职位
            Locator selectAllCheckbox = dialogPage.locator("div.applied-select-all input");
            if (selectAllCheckbox.count() > 0 && !selectAllCheckbox.isChecked()) {
                selectAllCheckbox.click();
                PlaywrightUtil.sleep(1);
            }

            // 获取相似职位列表
            Locator jobs = dialogPage.locator("div.recommend-job");
            int jobCount = jobs.count();

            if (jobCount == 0) {
                log.info("没有匹配到相似职位");
                return;
            }

            // 记录相似职位信息
            for (int i = 0; i < jobCount; i++) {
                try {
                    Locator jobElement = jobs.nth(i);
                    String jobName = safeGetText(jobElement, ".recommend-job__position");
                    String salary = safeGetText(jobElement, "span.recommend-job__demand__salary");
                    String years = safeGetText(jobElement, "span.recommend-job__demand__experience").replace("\n", " ");
                    String education = safeGetText(jobElement, "span.recommend-job__demand__educational").replace("\n", " ");
                    String companyName = safeGetText(jobElement, ".recommend-job__cname");
                    String companyTag = safeGetText(jobElement, ".recommend-job__demand__cinfo").replace("\n", " ");

                    Job job = new Job();
                    job.setJobName(jobName);
                    job.setSalary(salary);
                    job.setCompanyTag(companyTag);
                    job.setCompanyName(companyName);
                    job.setJobInfo(years + "·" + education);

                    log.info("投递【{}】公司【{}】岗位，薪资【{}】，要求【{}·{}】，规模【{}】",
                        companyName, jobName, salary, years, education, companyTag);
                    resultList.add(job);
                } catch (Exception e) {
                    log.debug("记录相似职位信息失败: {}", e.getMessage());
                }
            }

            // 点击投递按钮
            Locator postButton = dialogPage.locator("div.applied-select-all button");
            if (postButton.count() > 0) {
                postButton.click();
                PlaywrightUtil.sleep(2);
                log.info("相似职位投递成功！");
            }

        } catch (Exception e) {
            log.error("投递相似职位异常: {}", e.getMessage());
        }
    }

    /**
     * 检查是否达到投递上限
     */
    private boolean checkIsLimit() {
        try {
            PlaywrightUtil.sleep(1);
            Locator result = page.locator("//div[@class='a-job-apply-workflow']");
            if (result.count() > 0) {
                String text = result.textContent();
                if (text != null && text.contains("达到上限")) {
                    log.info("今日投递已达上限！");
                    isLimit = true;
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 设置最大页数（已废弃：使用“下一页不可点击”+最多50页）
     */
    private void setMaxPages() {
        // 保留方法避免旧调用报错，但不再依赖输入框改页码
        maxPage = 50;
    }

    /**
     * 构建基础搜索URL（不含关键词，由搜索框触发）
     */
    private String buildBaseUrl(String cityCode, int pageNum) {
        StringBuilder url = new StringBuilder(HOME_URL);
        url.append("jl").append(cityCode).append("/");
        url.append("p").append(pageNum).append("?");
        url.append(JobUtils.appendParam("sl", config.getSalary()));
        return url.toString();
    }

    /** 保留旧签名供单城市调用及既有测试使用。 */
    private String buildBaseUrl(int pageNum) {
        return buildBaseUrl(config.getCityCode(), pageNum);
    }

    private Set<String> normalizeBlacklist(Set<String> values) {
        if (values == null || values.isEmpty()) return Set.of();
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                normalized.add(value.trim().toLowerCase(Locale.ROOT));
            }
        }
        return normalized;
    }

    private String findBlacklistMatch(Set<String> blacklist, String target) {
        if (blacklist == null || blacklist.isEmpty() || target == null || target.isBlank()) return null;
        String normalizedTarget = target.toLowerCase(Locale.ROOT);
        return blacklist.stream().filter(normalizedTarget::contains).findFirst().orElse(null);
    }

    /**
     * 关键词搜索完成后再应用工作性质，避免智联搜索动作覆盖 et 参数。
     */
    private boolean applyJobNatureAfterKeywordSearch() {
        String jobNature = config == null ? null : config.getJobNature();
        if (jobNature == null || jobNature.isBlank() || "0".equals(jobNature)) {
            return true;
        }

        try {
            String currentUrl = page.url();
            String filteredUrl = replaceQueryParameter(currentUrl, "et", jobNature);
            if (!filteredUrl.equals(currentUrl)) {
                log.info("关键词搜索完成，开始应用工作性质筛选: et={}", jobNature);
                page.navigate(filteredUrl);
                page.waitForSelector(JOB_CARD_SELECTOR,
                        new Page.WaitForSelectorOptions().setTimeout(10_000));
            }
            return true;
        } catch (Exception e) {
            log.warn("关键词搜索后应用工作性质筛选失败: {}", e.getMessage());
            return false;
        }
    }

    private String replaceQueryParameter(String rawUrl, String name, String value) {
        if (rawUrl == null || rawUrl.isBlank() || name == null || name.isBlank()) {
            return rawUrl;
        }

        int fragmentIndex = rawUrl.indexOf('#');
        String fragment = fragmentIndex >= 0 ? rawUrl.substring(fragmentIndex) : "";
        String withoutFragment = fragmentIndex >= 0 ? rawUrl.substring(0, fragmentIndex) : rawUrl;
        int queryIndex = withoutFragment.indexOf('?');
        String base = queryIndex >= 0 ? withoutFragment.substring(0, queryIndex) : withoutFragment;
        String query = queryIndex >= 0 ? withoutFragment.substring(queryIndex + 1) : "";

        List<String> parameters = new ArrayList<>();
        if (!query.isBlank()) {
            for (String parameter : query.split("&")) {
                if (parameter.isBlank()) continue;
                String parameterName = parameter.contains("=")
                        ? parameter.substring(0, parameter.indexOf('='))
                        : parameter;
                if (!name.equals(parameterName)) parameters.add(parameter);
            }
        }
        if (value != null && !value.isBlank() && !"0".equals(value)) {
            parameters.add(name + "=" + value);
        }

        return base + (parameters.isEmpty() ? "" : "?" + String.join("&", parameters)) + fragment;
    }

    /**
     * 新版智联把完整岗位信息放在页面状态 positionList 中，卡片 DOM 只负责展示。
     * 直接读取该状态可得到平台真实的岗位编号、标题、公司和详情链接。
     */
    @SuppressWarnings("unchecked")
    private List<PageJob> collectPageJobsFromState() {
        Object raw = page.evaluate("""
                () => {
                  const list = globalThis.__INITIAL_STATE__ && globalThis.__INITIAL_STATE__.positionList;
                  if (!Array.isArray(list)) return [];
                  return list.map((p, index) => ({
                    index,
                    jobId: String(p.number || p.jobId || ''),
                    jobTitle: String(p.name || ''),
                    companyName: String(p.companyName || ''),
                    jobLink: String(p.positionUrl || p.positionURL || ''),
                    salary: String(p.salary60 || p.salaryReal || ''),
                    location: [p.workCity, p.cityDistrict].filter(Boolean).join('·'),
                    experience: String(p.workingExp || ''),
                    degree: String(p.education || ''),
                    alreadyApplied: Boolean(p.hasAppliedPosition)
                  }));
                }
                """);

        List<PageJob> jobs = new ArrayList<>();
        if (!(raw instanceof List<?> rows)) return jobs;
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> values)) continue;
            String jobId = valueAsString(values.get("jobId"));
            String title = valueAsString(values.get("jobTitle"));
            String company = valueAsString(values.get("companyName"));
            if (jobId.isBlank() || title.isBlank() || company.isBlank()) {
                log.warn("positionList 返回不完整岗位，停止使用该条数据：jobId={}，title={}，company={}",
                        jobId, title, company);
                continue;
            }
            int index = values.get("index") instanceof Number number ? number.intValue() : jobs.size();
            jobs.add(new PageJob(
                    index,
                    jobId,
                    title,
                    company,
                    valueAsString(values.get("jobLink")),
                    valueAsString(values.get("salary")),
                    valueAsString(values.get("location")),
                    valueAsString(values.get("experience")),
                    valueAsString(values.get("degree")),
                    Boolean.TRUE.equals(values.get("alreadyApplied"))));
        }
        log.info("已从智联 positionList 获取 {} 条完整岗位元数据", jobs.size());
        return jobs;
    }

    private String valueAsString(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private void saveJobIfAbsent(PageJob job) {
        try {
            if (zhilianService.existsByJobId(job.jobId)) return;
            ZhilianJobDataEntity entity = new ZhilianJobDataEntity();
            entity.setJobId(job.jobId);
            entity.setJobTitle(job.jobTitle);
            entity.setJobLink(job.jobLink);
            entity.setSalary(job.salary);
            entity.setLocation(job.location);
            entity.setExperience(job.experience);
            entity.setDegree(job.degree);
            entity.setCompanyName(job.companyName);
            entity.setDeliveryStatus("未投递");
            zhilianService.insertJob(entity);
        } catch (Exception e) {
            log.warn("保存岗位数据失败：{} | {}，原因：{}", job.companyName, job.jobTitle, e.getMessage());
        }
    }

    /**
     * 关闭“已向对方发送简历和打招呼语”弹窗。弹窗不消失时遮罩会拦截下一张岗位卡片。
     */
    private boolean closeDeliverySuccessDialog() {
        try {
            Locator modal = page.locator(".deliver-greeting-modal").first();
            if (modal.count() == 0 || !modal.isVisible()) return true;

            Locator closeButton = modal.locator("button.deliver-greeting-modal__close, button[aria-label='关闭']").first();
            if (closeButton.count() == 0 || !closeButton.isVisible()) {
                closeButton = modal.getByText("留在此页", new Locator.GetByTextOptions().setExact(true)).first();
            }
            if (closeButton.count() == 0 || !closeButton.isVisible()) {
                log.warn("成功弹窗已出现，但未找到右上角关闭按钮或“留在此页”按钮");
                return false;
            }

            closeButton.click();
            long deadline = System.currentTimeMillis() + 3_000;
            while (System.currentTimeMillis() < deadline) {
                if (modal.count() == 0 || !modal.isVisible()) {
                    log.info("已关闭智联投递成功弹窗");
                    return true;
                }
                page.waitForTimeout(100);
            }
            return modal.count() == 0 || !modal.isVisible();
        } catch (Exception e) {
            log.warn("关闭智联投递成功弹窗失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 查找搜索关键词输入框（多候选选择器，提高鲁棒性）
     */
    private Locator findKeywordInput() {
        String[] candidates = new String[] {
            "input[placeholder*='职位']",
            "input[placeholder*='公司']",
            "input[name='kw']",
            "input[type='text']",
            "input[class*='search'], input[class*='sou'], input[class*='input']"
        };
        for (String sel : candidates) {
            try {
                Locator lc = page.locator(sel);
                if (lc != null && lc.count() > 0) {
                    return lc.first();
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    /**
     * 判断“下一页”是否不可点击
     */
    private boolean isNextDisabled() {
        try {
            Locator nextBtn = page.locator("a.soupager__btn:has-text(\"下一页\")");
            if (nextBtn.count() == 0) return true;
            String cls = null;
            try { cls = nextBtn.first().getAttribute("class"); } catch (Exception ignored) {}
            String disabledAttr = null;
            try { disabledAttr = nextBtn.first().getAttribute("disabled"); } catch (Exception ignored) {}
            if (cls != null && cls.contains("soupager__btn--disable")) return true;
            return disabledAttr != null && ("disabled".equalsIgnoreCase(disabledAttr) || "true".equalsIgnoreCase(disabledAttr));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 安全获取文本内容
     */
    private String safeGetText(Locator parent, String selector) {
        try {
            Locator element = parent.locator(selector);
            if (element.count() > 0) {
                return element.textContent();
            }
        } catch (Exception e) {
            log.debug("获取文本失败: {}", e.getMessage());
        }
        return "";
    }

    private boolean isNewJobCard(Locator card) {
        try {
            return card.locator(".job-card__salary, .job-card__company-name, [class*='job-card__title']").count() > 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * 格式化时长
     */
    private String formatDuration(long millis) {
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;

        if (hours > 0) {
            return String.format("%d小时%d分钟", hours, minutes % 60);
        } else if (minutes > 0) {
            return String.format("%d分钟%d秒", minutes, seconds % 60);
        } else {
            return String.format("%d秒", seconds);
        }
    }

    /**
     * 将当前页面内容保存到项目内 page.html，覆盖原文件
     */
    private void saveCurrentPageHtml() {
        try {
            String html = page.content();
            java.nio.file.Path path = java.nio.file.Paths.get("src/main/java/com/getjobs/worker/zhilian/page.html");
            java.nio.file.Files.createDirectories(path.getParent());
            java.nio.file.Files.write(path, html.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException("写入 page.html 失败", e);
        }
    }

    /**
     * 发送进度消息
     */
    private void sendProgress(String message, Integer current, Integer total) {
        if (progressCallback != null) {
            progressCallback.accept(message, current, total);
        }
    }

    /**
     * 检查是否应该停止
     */
    private boolean shouldStop() {
        return shouldStopCallback != null && shouldStopCallback.get();
    }
}
