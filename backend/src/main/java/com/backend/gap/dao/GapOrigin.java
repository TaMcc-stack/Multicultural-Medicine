package com.backend.gap.dao;

/**
 * 一条知识缺口是**从哪个入口**来的。
 *
 * <p>由每个写入方自己声明，而不是靠数据库默认值去猜——默认值只能表达「表刚建时是谁在写」，
 * 表达不了「这一行这次是被谁碰到的」。目前有三个写入方：</p>
 *
 * <ul>
 *   <li>{@link #SEARCH} 高级检索未命中，**自动**登记（GapService.record）</li>
 *   <li>{@link #CHAT} 智能对话未命中、用户**主动**点了【反馈此问题】（GapService.chatFeedback）</li>
 *   <li>{@link #BOARD} 留言板反馈被管理员一键转为缺口（FeedbackService.toGap）</li>
 * </ul>
 *
 * <p>注意 SEARCH 与 CHAT 不是互斥的：同一行可以既被检索记过、又被对话反馈过，
 * 两列标记会同时为 1（见 schema.sql 里那段加列说明）。BOARD 则是两个标记都为 0 的那种。</p>
 */
public enum GapOrigin {
    SEARCH,
    CHAT,
    BOARD
}
