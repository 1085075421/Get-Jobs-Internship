package com.getjobs.worker.zhilian;

import com.getjobs.application.service.ZhilianService;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ZhilianJobNatureTest {

    @Test
    void initialSearchUrlDoesNotApplyInternshipBeforeKeyword() throws Exception {
        ZhiLian worker = new ZhiLian(mock(ZhilianService.class));
        ZhilianConfig config = configWithJobNature("4");
        worker.setConfig(config);

        String url = buildBaseUrl(worker);

        assertFalse(url.contains("&et="));
    }

    @Test
    void unlimitedDoesNotAddEmploymentTypeToSearchUrl() throws Exception {
        ZhiLian worker = new ZhiLian(mock(ZhilianService.class));
        ZhilianConfig config = configWithJobNature("0");
        worker.setConfig(config);

        String url = buildBaseUrl(worker);

        assertFalse(url.contains("&et="));
    }

    @Test
    void internshipIsAddedAfterKeywordWithoutLosingExistingSearchParameters() throws Exception {
        ZhiLian worker = new ZhiLian(mock(ZhilianService.class));
        Method method = ZhiLian.class.getDeclaredMethod(
                "replaceQueryParameter", String.class, String.class, String.class);
        method.setAccessible(true);

        String result = (String) method.invoke(worker,
                "https://www.zhaopin.com/jobs/?jl=530&pageMode=search&kw=CV%E7%AE%97%E6%B3%95%E5%AE%9E%E4%B9%A0",
                "et", "4");

        assertEquals(
                "https://www.zhaopin.com/jobs/?jl=530&pageMode=search&kw=CV%E7%AE%97%E6%B3%95%E5%AE%9E%E4%B9%A0&et=4",
                result);
    }

    @Test
    void existingEmploymentTypeIsReplacedInsteadOfDuplicated() throws Exception {
        ZhiLian worker = new ZhiLian(mock(ZhilianService.class));
        Method method = ZhiLian.class.getDeclaredMethod(
                "replaceQueryParameter", String.class, String.class, String.class);
        method.setAccessible(true);

        String result = (String) method.invoke(worker,
                "https://www.zhaopin.com/jobs/?jl=530&et=2&kw=Java#list",
                "et", "4");

        assertEquals("https://www.zhaopin.com/jobs/?jl=530&kw=Java&et=4#list", result);
    }

    @Test
    void readsRealJobMetadataFromZhilianPositionList() throws Exception {
        Page page = mock(Page.class);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("index", 0);
        row.put("jobId", "CC123J456");
        row.put("jobTitle", "计算机视觉算法实习生");
        row.put("companyName", "测试科技有限公司");
        row.put("jobLink", "https://www.zhaopin.com/jobdetail/CC123J456.htm");
        row.put("salary", "200-300元/天");
        row.put("location", "北京·海淀区");
        row.put("experience", "经验不限");
        row.put("degree", "本科");
        row.put("alreadyApplied", false);
        when(page.evaluate(org.mockito.ArgumentMatchers.anyString())).thenReturn(List.of(row));

        ZhiLian worker = new ZhiLian(mock(ZhilianService.class));
        worker.setPage(page);
        Method method = ZhiLian.class.getDeclaredMethod("collectPageJobsFromState");
        method.setAccessible(true);
        List<?> jobs = (List<?>) method.invoke(worker);

        assertEquals(1, jobs.size());
        assertEquals("CC123J456", readField(jobs.get(0), "jobId"));
        assertEquals("计算机视觉算法实习生", readField(jobs.get(0), "jobTitle"));
        assertEquals("测试科技有限公司", readField(jobs.get(0), "companyName"));
    }

    private ZhilianConfig configWithJobNature(String jobNature) {
        ZhilianConfig config = new ZhilianConfig();
        config.setKeywords(List.of("视觉算法实习"));
        config.setCityCode("530");
        config.setSalary("0");
        config.setJobNature(jobNature);
        return config;
    }

    private String buildBaseUrl(ZhiLian worker) throws Exception {
        Method method = ZhiLian.class.getDeclaredMethod("buildBaseUrl", int.class);
        method.setAccessible(true);
        return (String) method.invoke(worker, 1);
    }

    private Object readField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
