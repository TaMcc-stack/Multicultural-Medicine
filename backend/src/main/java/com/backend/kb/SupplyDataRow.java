package com.backend.kb;

/**
 * 整合资料里「患病率数据」表的一行。
 *
 * <p>列与工作台上的表格一一对应：民族 / 研究地区 / 年龄范围 / 样本量 / 指标类型 / 数值 / 来源文献。
 * 全部可空——抽不到的项就该空着让管理员补，而不是编一个默认值出来（编出来的数字会被当成依据）。</p>
 *
 * @param source 来源文献（期刊名或「作者+年份」）。这一列是硬要求：整合资料的价值就在于
 *               每条数据都能追到具体文献，缺了它这份表就退化成《数据资料》那种没有出处的汇编
 */
public record SupplyDataRow(
        String ethnicity,
        String region,
        String ageRange,
        String sampleSize,
        String metric,
        String value,
        String source
) {

    /** 至少要有数值，否则这不是数据行而是叙述——与 AI 侧 `_parse_payload` 的判据一致 */
    public boolean hasValue() {
        return value != null && !value.isBlank();
    }

    /** 原文没点名民族时回落到缺口本身的民族（补录本就是冲着这个组合来的） */
    public String eth(String fallback) {
        return (ethnicity == null || ethnicity.isBlank()) ? fallback : ethnicity.trim();
    }
}
