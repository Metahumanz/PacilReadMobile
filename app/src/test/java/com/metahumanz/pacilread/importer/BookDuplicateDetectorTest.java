package com.metahumanz.pacilread.importer;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

public class BookDuplicateDetectorTest {
    @Test
    public void exactHashTakesPriorityOverMetadata() {
        BookDuplicateDetector.Candidate existing = new BookDuplicateDetector.Candidate(
                "old", "旧标题", "甲", "abc");
        BookDuplicateDetector.Candidate incoming = new BookDuplicateDetector.Candidate(
                "new", "新标题", "乙", "ABC");

        Map<String, BookDuplicateDetector.MatchType> result = BookDuplicateDetector.detect(
                Collections.singletonList(existing), Collections.singletonList(incoming));

        assertEquals(BookDuplicateDetector.MatchType.EXACT_CONTENT, result.get("new"));
    }

    @Test
    public void detectsMetadataAndDuplicatesInsideIncomingBatch() {
        BookDuplicateDetector.Candidate first = new BookDuplicateDetector.Candidate(
                "first", "同一本书", "作者", "one");
        BookDuplicateDetector.Candidate second = new BookDuplicateDetector.Candidate(
                "second", " 同一本书 ", "作者", "two");
        BookDuplicateDetector.Candidate third = new BookDuplicateDetector.Candidate(
                "third", "另一标题", "其他", "one");

        Map<String, BookDuplicateDetector.MatchType> result = BookDuplicateDetector.detect(
                Collections.emptyList(), Arrays.asList(first, second, third));

        assertEquals(BookDuplicateDetector.MatchType.SAME_TITLE_AUTHOR, result.get("second"));
        assertEquals(BookDuplicateDetector.MatchType.EXACT_CONTENT, result.get("third"));
    }
}
