package com.getjobs.worker.campus;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CampusPlatformWorkerBlacklistTest {
    @Test
    void matchesCompanyAndJobKeywordsByContainmentIgnoringCase() {
        Set<String> blacklist = new LinkedHashSet<>(Set.of("BILIbili", "销售"));

        assertEquals("BILIbili", CampusPlatformWorker.findBlacklistMatch(
                blacklist, "上海哔哩哔哩科技有限公司 BILIBILI"));
        assertEquals("销售", CampusPlatformWorker.findBlacklistMatch(
                blacklist, "大客户销售实习生"));
    }

    @Test
    void doesNotMatchUnrelatedOrBlankValues() {
        Set<String> blacklist = Set.of("销售", "测试");

        assertNull(CampusPlatformWorker.findBlacklistMatch(blacklist, "算法实习生"));
        assertNull(CampusPlatformWorker.findBlacklistMatch(blacklist, ""));
        assertNull(CampusPlatformWorker.findBlacklistMatch(Set.of(), "算法实习生"));
    }
}
