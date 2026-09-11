package com.graduation.analytics.ai;

import com.graduation.analytics.ai.sql.AiSqlException;
import com.graduation.analytics.ai.sql.SqlPolicy;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 问句层注入筛（§19.5 纵深防御第二层）。
 *
 * <p>2026-09-11 真机验收发现：安全问数原先只有「AST 校验」一层。当 LLM 未配置、
 * 走规则回退时，「删除所有指标数据」这类攻击问句会被<b>静默</b>映射成一条无害的趋势
 * SELECT —— 既没产出危险 SQL，也没在 {@code ai_query_history} 留下「被拒绝」的痕迹，
 * 于是攻击集全绿而防线根本没被触发（验收假绿）。</p>
 *
 * <p>本类在<strong>生成任何 SQL 之前</strong>先拒一次：命中即抛
 * {@link SqlPolicy#SQL_QUESTION_UNSAFE}，由 {@link TextToSqlService} 记为 REJECTED
 * 并照常写审计行（§19.6「拒绝也留痕」）。它是 AST 校验的补充而非替代：
 * AST 校验仍然是唯一能放行 SQL 的关口。</p>
 */
final class QuestionSafetyScreen {

    private QuestionSafetyScreen() {
    }

    /** 写操作 / 结构变更 / 提权 / 文件与外联（大小写无关，按词边界匹配） */
    private static final Pattern DML_DDL = Pattern.compile(
            "\\b(drop|delete|truncate|alter|insert|update|grant|revoke|create|replace|merge|call|exec|execute"
                    + "|shutdown|rename|load_file|load\\s+data|into\\s+outfile|into\\s+dumpfile)\\b");

    /** 用户直接粘贴 SQL（自然语言问数不接受 SQL 文本，SELECT ... FROM 即可判为粘贴） */
    private static final Pattern PASTED_SQL = Pattern.compile("\\bselect\\b[\\s\\S]{0,200}?\\bfrom\\b");

    /** 组合/注释/终结符/系统库：任何一处出现都判为注入尝试 */
    private static final Pattern INJECTION_NOISE = Pattern.compile(
            "(--|/\\*|\\*/|;|\\bunion\\b|\\bjoin\\b|information_schema|performance_schema|mysql\\."
                    + "|pg_catalog|sysobjects)");

    /** 中文写操作/绕过类动词（英文关键词之外的高频说法） */
    private static final List<String> CN_WRITE_VERBS = List.of(
            "删除", "清空", "删库", "删表", "篡改", "改写", "提权", "绕过", "注入");

    /**
     * 校验问句；命中即抛 {@link AiSqlException}（错误码 {@link SqlPolicy#SQL_QUESTION_UNSAFE}）。
     *
     * @param question 用户原始问句
     */
    static void check(String question) {
        String raw = question == null ? "" : question;
        if (raw.isBlank()) {
            throw new AiSqlException(SqlPolicy.SQL_QUESTION_UNSAFE, "问句为空，无法生成只读指标查询");
        }
        String q = raw.toLowerCase(Locale.ROOT);
        reject(DML_DDL.matcher(q), raw, "数据库写操作/结构变更关键词");
        reject(PASTED_SQL.matcher(q), raw, "疑似直接粘贴 SQL 语句");
        reject(INJECTION_NOISE.matcher(q), raw, "SQL 注释/多语句/组合查询/系统库");
        for (String verb : CN_WRITE_VERBS) {
            if (raw.contains(verb)) {
                throw new AiSqlException(SqlPolicy.SQL_QUESTION_UNSAFE,
                        "问句含「" + verb + "」：本接口只提供自然语言的只读指标查询，拒绝执行写操作/绕过指令");
            }
        }
    }

    private static void reject(Matcher m, String raw, String reason) {
        if (m.find()) {
            throw new AiSqlException(SqlPolicy.SQL_QUESTION_UNSAFE,
                    "问句命中「" + reason + "」（" + m.group() + "）：本接口只提供自然语言的只读指标查询");
        }
    }
}
