package com.yenaly.han1meviewer.worker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeRangeValidatorTest {
    @Test fun acceptsExactResumeRange() {
        assertTrue(ResumeRangeValidator.matches("bytes 4096-9999/10000", 4096, 10000))
        assertTrue(ResumeRangeValidator.matches("bytes 4096-8191/10000", 4096, 10000))
    }

    @Test fun rejectsWrongOffsetsAndLengths() {
        assertFalse(ResumeRangeValidator.matches("bytes 0-9999/10000", 4096, 10000))
        assertFalse(ResumeRangeValidator.matches("bytes 4096-9999/10001", 4096, 10000))
        assertFalse(ResumeRangeValidator.matches("bytes 4096-10000/10000", 4096, 10000))
        assertFalse(ResumeRangeValidator.matches("bytes 4096-1000/10000", 4096, 10000))
        assertFalse(ResumeRangeValidator.matches(null, 4096, 10000))
        assertFalse(ResumeRangeValidator.matches("bytes */10000", 4096, 10000))
    }

    @Test fun acceptsFullAndPartialResponsesOnlyAtTheirExpectedPosition() {
        assertTrue(ResumeRangeValidator.accepts(200, null, "10000", 0, 10000))
        assertTrue(ResumeRangeValidator.accepts(200, null, null, 0, 10000))
        assertTrue(ResumeRangeValidator.accepts(206, "bytes 4096-9999/10000", "5904", 4096, 10000))
        assertFalse(ResumeRangeValidator.accepts(200, null, "10000", 4096, 10000))
        assertFalse(ResumeRangeValidator.accepts(200, null, "9000", 0, 10000))
        assertFalse(ResumeRangeValidator.accepts(206, "bytes 0-9999/10000", "10000", 4096, 10000))
        assertFalse(ResumeRangeValidator.accepts(416, "bytes */10000", null, 4096, 10000))
    }
}
