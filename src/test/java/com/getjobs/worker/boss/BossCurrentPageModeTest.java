package com.getjobs.worker.boss;

import com.getjobs.application.service.AiService;
import com.getjobs.application.service.BossResumeImageService;
import com.getjobs.application.service.BossService;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.getjobs.worker.boss.Locators.JOB_LIST_SELECTOR;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BossCurrentPageModeTest {

    @Test
    void blankKeywordsUseCurrentPageMode() {
        assertFalse(Boss.hasConfiguredSearchKeywords(null));
        assertFalse(Boss.hasConfiguredSearchKeywords(List.of()));
        assertFalse(Boss.hasConfiguredSearchKeywords(List.of("", "  ")));
    }

    @Test
    void anyNonBlankKeywordKeepsSearchMode() {
        assertTrue(Boss.hasConfiguredSearchKeywords(List.of("", "图像算法")));
    }

    @Test
    void recognizesBossJobListButRejectsOtherBossPages() {
        assertTrue(Boss.isBossJobListUrl("https://www.zhipin.com/web/geek/jobs?experience=108"));
        assertFalse(Boss.isBossJobListUrl("https://www.zhipin.com/web/geek/chat"));
        assertFalse(Boss.isBossJobListUrl("https://example.com/web/geek/jobs"));
    }

    @Test
    void validatingCurrentListDoesNotNavigateAwayFromManualFilters() throws Exception {
        Page page = mock(Page.class);
        Locator cards = mock(Locator.class);
        when(page.url()).thenReturn("https://www.zhipin.com/web/geek/jobs?experience=108");
        when(page.locator(JOB_LIST_SELECTOR)).thenReturn(cards);
        when(cards.count()).thenReturn(12);

        Boss boss = new Boss(mock(BossService.class), mock(AiService.class), mock(BossResumeImageService.class));
        boss.setPage(page);

        var validation = Boss.class.getDeclaredMethod("validateCurrentJobList");
        validation.setAccessible(true);
        validation.invoke(boss);

        verify(page, never()).navigate(anyString());
    }
}
