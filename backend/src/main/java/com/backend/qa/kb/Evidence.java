package com.backend.qa.kb;

/**
 * 证据片段实体 —— AI 回答中的每条引用通过 evidence_id 对应到该记录
 */
public record Evidence(
        Long id,
        Long paperId,
        String topic,
        String content
) {
}
