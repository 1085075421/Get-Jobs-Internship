package com.getjobs.application.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Job51ResumeIndexTest {
    @Test
    void defaultsInvalidResumeIndexesToFirstResume() {
        assertEquals(1, Job51Service.normalizeResumeIndex(null));
        assertEquals(1, Job51Service.normalizeResumeIndex(0));
        assertEquals(1, Job51Service.normalizeResumeIndex(-2));
        assertEquals(3, Job51Service.normalizeResumeIndex(3));
    }
}
