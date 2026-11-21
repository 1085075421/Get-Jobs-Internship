package com.getjobs.worker.job51;

import com.getjobs.application.service.Job51Service;
import com.microsoft.playwright.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

/** 使用本地模拟页面验证逐个投递，不连接51job，也不会产生真实申请。 */
@EnabledIfEnvironmentVariable(named = "JOB51_DOM_TEST", matches = "true")
class Job51SingleDeliveryDomTest {
    static Playwright playwright;
    static Browser browser;
    BrowserContext context;
    Page page;

    @BeforeAll
    static void launch() {
        var environment = new java.util.HashMap<>(System.getenv());
        environment.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
        playwright = Playwright.create(new Playwright.CreateOptions().setEnv(environment));
        String chrome = System.getenv("BOSS_TEST_CHROME");
        var options = new BrowserType.LaunchOptions().setHeadless(true);
        if (chrome != null) options.setExecutablePath(Path.of(chrome));
        browser = playwright.chromium().launch(options);
    }

    @AfterAll
    static void shutdown() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
    }

    @BeforeEach
    void setup() {
        context = browser.newContext();
        page = context.newPage();
    }

    @AfterEach
    void cleanup() {
        context.close();
    }

    @Test
    void deliversOneByOneSelectsConfiguredResumeAndMarksOnlyAfterSuccess() {
        page.setContent("""
                <div class="job-card">
                  <a class="jname text-cut" href="/pc/jobdetail?jobId=999">校招测试岗位</a>
                  <span class="tag">校招</span><span class="cname text-cut">校招公司</span>
                  <button onclick="window.campusClicked=true">投递</button>
                </div>
                <div class="job-card">
                  <a class="jname text-cut" href="/pc/jobdetail?jobId=1001">Java开发</a>
                  <span class="cname text-cut">甲公司</span><button onclick="openResume(1001)">投递</button>
                </div>
                <div class="job-card">
                  <a class="jname text-cut" href="/pc/jobdetail?jobId=1002">Python开发</a>
                  <span class="cname text-cut">乙公司</span><button onclick="openResume(1002)">投递</button>
                </div>
                <div id="modal"></div>
                <script>
                  window.selectedResumes = [];
                  window.campusClicked = false;
                  window.openResume = (jobId) => {
                    document.querySelector('#modal').innerHTML = `
                      <div role="dialog" class="el-dialog attachment_resume_dialog">
                        <h2>选择需要同步发送的附件简历</h2>
                        <div class="attachment_list">
                          <div class="attachment_item" data-value="1" onclick="selectResume(this)"><div class="radio"></div><div class="name">第一份</div></div>
                          <div class="attachment_item" data-value="2" onclick="selectResume(this)"><div class="radio"></div><div class="name">第二份</div></div>
                        </div>
                        <button onclick="sendResume(${jobId})">发送</button>
                      </div>`;
                  };
                  window.selectResume = (item) => {
                    document.querySelectorAll('.radio').forEach(x => x.classList.remove('selected'));
                    item.querySelector('.radio').classList.add('selected');
                    window.currentResume = Number(item.dataset.value);
                  };
                  window.sendResume = (jobId) => {
                    if (!window.currentResume) return;
                    window.selectedResumes.push(window.currentResume);
                    document.querySelector('#modal').innerHTML = `
                      <div role="dialog" class="success-popup"><img role="button" class="close-button" onclick="this.parentElement.remove()">
                      <h2>投递成功</h2><span data-job="${jobId}"></span></div>`;
                  };
                </script>
                """);

        Job51Service service = mock(Job51Service.class);
        Job51 worker = new Job51(service);
        Job51Config config = new Job51Config();
        config.setResumeIndex(2);
        worker.setPage(page);
        worker.setConfig(config);
        worker.setShouldStopCallback(() -> false);

        worker.deliverCurrentPage();

        assertEquals(List.of(2, 2), page.evaluate("() => window.selectedResumes"));
        assertEquals(false, page.evaluate("() => window.campusClicked"));
        verify(service).markDelivered(1001L);
        verify(service).markDelivered(1002L);
        verifyNoMoreInteractions(service);
    }

    @Test
    void doesNotMarkDatabaseWhenPageReportsDeliveryFailure() {
        page.setContent("""
                <div class="job-card">
                  <a class="jname text-cut" href="/pc/jobdetail?jobId=2001">测试岗位</a>
                  <span class="cname text-cut">测试公司</span><button onclick="openResume()">投递</button>
                </div>
                <div id="modal"></div>
                <script>
                  window.openResume = () => document.querySelector('#modal').innerHTML = `
                    <div role="dialog"><h2>选择需要同步发送的附件简历</h2>
                    <label><input type="radio" name="resume">第一份</label>
                    <button onclick="document.querySelector('#modal').innerHTML='<div role=dialog><h2>投递失败</h2></div>'">发送</button></div>`;
                </script>
                """);

        Job51Service service = mock(Job51Service.class);
        Job51 worker = new Job51(service);
        Job51Config config = new Job51Config();
        config.setResumeIndex(1);
        worker.setPage(page);
        worker.setConfig(config);
        worker.setShouldStopCallback(() -> false);

        worker.deliverCurrentPage();

        verifyNoInteractions(service);
    }

    @Test
    void stopsCurrentPageWhenAttachmentDialogCannotBeSelected() {
        page.setContent("""
                <div class="job-card">
                  <a class="jname" href="/pc/jobdetail?jobId=3001">第一个岗位</a>
                  <span class="cname">甲公司</span><button onclick="openBrokenDialog()">投递</button>
                </div>
                <div class="job-card">
                  <a class="jname" href="/pc/jobdetail?jobId=3002">第二个岗位</a>
                  <span class="cname">乙公司</span><button onclick="window.secondClicked=true">投递</button>
                </div>
                <div id="modal"></div>
                <script>
                  window.secondClicked = false;
                  window.openBrokenDialog = () => document.querySelector('#modal').innerHTML =
                    '<div role="dialog" class="el-dialog attachment_resume_dialog"><h2>选择需要同步发送的附件简历</h2><div class="attachment_list"></div><button>发送</button></div>';
                </script>
                """);

        Job51Service service = mock(Job51Service.class);
        Job51 worker = new Job51(service);
        worker.setPage(page);
        worker.setConfig(new Job51Config());
        worker.setShouldStopCallback(() -> false);

        worker.deliverCurrentPage();

        assertEquals(false, page.evaluate("() => window.secondClicked"));
        assertEquals(1, page.locator(".attachment_resume_dialog:visible").count());
        verifyNoInteractions(service);
    }
}
