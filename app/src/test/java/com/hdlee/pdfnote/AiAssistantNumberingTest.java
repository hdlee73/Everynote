package com.hdlee.pdfnote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class AiAssistantNumberingTest {
    @Test public void stripsMarkersFromNormalReply() {
        String[] r = AiAssistant.parseNumbered("[1] 안녕하세요\n[2] 반갑습니다\n이어지는 말", 2);
        assertEquals("안녕하세요", r[0]);
        assertEquals("반갑습니다 이어지는 말", r[1]);
    }

    @Test public void handlesInlineAndOddMarkers() {
        String[] r = AiAssistant.parseNumbered("[1] [1] 첫째 [2] 둘째\n【3】 셋째", 3);
        assertEquals("첫째", r[0]);
        assertEquals("둘째", r[1]);
        assertEquals("셋째", r[2]);
    }

    @Test public void missingEntriesStayNull() {
        String[] r = AiAssistant.parseNumbered("[2] 둘째", 2);
        assertNull(r[0]);
        assertEquals("둘째", r[1]);
    }

    @Test public void cleanRemovesStrayMarkersOnlyWhenSourceHasNone() {
        assertEquals("가 나", AiAssistant.clean("[9] 가\n[3] 나", "source"));
        assertEquals("see [3] here", AiAssistant.clean("see [3] here", "ref [3]"));
    }
}
