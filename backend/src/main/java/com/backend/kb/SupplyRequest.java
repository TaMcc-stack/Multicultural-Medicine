package com.backend.kb;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

/**
 * 「整理成文档 / 提交入库」的请求：把工作台上核对好的结构化数据合成一份 Markdown 文档。
 *
 * <p><b>为什么是结构化字段而不是自由文本</b>：之前让管理员粘贴正文，文档就是一大段论文原文的
 * 文本流——读者看不出哪条是患病率、哪条是危险因素，检索时也没有可对齐的结构。
 * 现在按固定模板收：一张数据表 + 两份列表，渲染出来的文档天然带表格与列表。</p>
 *
 * <p><b>为什么必须有来源文献</b>：整合资料的价值在于每条数据都能追到具体文献。
 * 缺出处的数据看起来有据可查、实际无从核对，比不写更糟。</p>
 *
 * @param gapId  补录来源的缺口；入库进待审核，审核通过且索引同步成功后才结掉它
 * @param intent 想了解的方面（意图码），用于生成标题与文档内的小标题
 * @param title  文档标题；不传则用「民族+疾病+方面+资料整合」
 * @param region 研究地区（如「云南大理」）——文档级「适用范围」元数据，回答据此说明数据适用于哪里
 * @param ageRange 年龄范围（如「≥18岁」）——文档级「适用范围」元数据
 * @param sampleSize 样本量（如「5439人」）——文档级「适用范围」元数据
 * @param year  研究年份（如「2022年」）——文档级「适用范围」元数据
 * @param rows   患病率数据表的行（顺序即展示顺序）
 * @param risks  危险因素列表
 * @param advice 专家建议列表——**只摘录原文里写的**，不由系统生成任何医疗建议
 */
public record SupplyRequest(
        Long gapId,
        @NotBlank(message = "缺少民族") String ethnicity,
        @NotBlank(message = "缺少疾病") String disease,
        String intent,
        String title,
        @NotBlank(message = "缺少研究地区（适用范围）") String region,
        @NotBlank(message = "缺少年龄范围（适用范围）") String ageRange,
        @NotBlank(message = "缺少样本量（适用范围）") String sampleSize,
        @NotBlank(message = "缺少研究年份（适用范围）") String year,
        List<SupplyDataRow> rows,
        List<String> risks,
        List<String> advice
) {}
