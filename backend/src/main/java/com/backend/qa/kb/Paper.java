package com.backend.qa.kb;

/**
 * 论文（知识库资料）实体
 */
public record Paper(
        Long id,
        String title,
        String ethnicity,
        String disease,
        String population,
        String studyYear,
        String findings,
        String limitation,
        String source
) {
}
