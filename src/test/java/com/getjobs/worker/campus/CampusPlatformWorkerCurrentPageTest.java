package com.getjobs.worker.campus;

import com.getjobs.application.service.CampusPlatformService;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CampusPlatformWorkerCurrentPageTest {

    @Test
    void recognizesNowcoderJobListButRejectsOtherPages() {
        assertTrue(CampusPlatformWorker.isNowcoderJobListUrl(
                "https://www.nowcoder.com/jobs/intern/center?recruitType=2&city=杭州"));
        assertFalse(CampusPlatformWorker.isNowcoderJobListUrl("https://www.nowcoder.com/profile/123"));
        assertFalse(CampusPlatformWorker.isNowcoderJobListUrl("https://example.com/jobs/intern/center"));
    }

    @Test
    void validatingCurrentListDoesNotNavigateAwayFromManualFilters() {
        Page page = mock(Page.class);
        Locator links = mock(Locator.class);
        when(page.url()).thenReturn("https://www.nowcoder.com/jobs/intern/center?city=杭州&city=宁波");
        when(page.locator("a[href*='/jobs/detail/']")).thenReturn(links);
        when(links.count()).thenReturn(12);

        CampusPlatformWorker worker = new CampusPlatformWorker(
                "nowcoder", page, mock(CampusPlatformService.class), () -> false, ignored -> {}, Set.of(), Set.of());

        worker.validateCurrentNowcoderJobList();

        verify(page, never()).navigate(anyString());
    }

    @Test
    void recognizesNowcoderImmediateApplyButton() throws Exception {
        Page page = mock(Page.class);
        Locator apply = mock(Locator.class);
        when(page.getByText(eq("立即申请"), any(Page.GetByTextOptions.class))).thenReturn(apply);
        when(apply.first()).thenReturn(apply);
        when(apply.count()).thenReturn(1);
        when(apply.isVisible()).thenReturn(true);

        CampusPlatformWorker worker = new CampusPlatformWorker(
                "nowcoder", page, mock(CampusPlatformService.class), () -> false, ignored -> {}, Set.of(), Set.of());
        var finder = CampusPlatformWorker.class.getDeclaredMethod("findNowcoderApplyButton", Page.class);
        finder.setAccessible(true);

        assertSame(apply, finder.invoke(worker, page));
    }

    @Test
    void confirmsNowcoderResumeDialogByClickingSubmitResume() throws Exception {
        Page page = mock(Page.class);
        Locator submitResume = mock(Locator.class);
        when(page.locator(anyString())).thenReturn(submitResume);
        when(submitResume.filter(any(Locator.FilterOptions.class))).thenReturn(submitResume);
        when(submitResume.nth(0)).thenReturn(submitResume);
        when(submitResume.count()).thenReturn(1);
        when(submitResume.isVisible()).thenReturn(true);
        when(submitResume.innerText()).thenReturn("投递简历");

        CampusPlatformWorker worker = new CampusPlatformWorker(
                "nowcoder", page, mock(CampusPlatformService.class), () -> false, ignored -> {}, Set.of(), Set.of());
        var confirm = CampusPlatformWorker.class.getDeclaredMethod("clickConfirmIfPresent", Page.class);
        confirm.setAccessible(true);
        assertTrue((Boolean) confirm.invoke(worker, page));

        verify(submitResume, times(1)).click(any(Locator.ClickOptions.class));
    }
}
