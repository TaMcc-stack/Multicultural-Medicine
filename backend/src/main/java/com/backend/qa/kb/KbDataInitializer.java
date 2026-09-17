package com.backend.qa.kb;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 知识库演示数据初始化：白族 + 糖尿病验证场景
 *
 * 注意：以下论文与证据均为 POC 演示用途构造的示例数据（source 字段已标注），
 * 用于验证「回答 → 证据 → 论文来源」的溯源链路，非真实文献结论。
 * MVP 阶段将替换为正式整理的多民族健康研究资料。
 */
@Component
public class KbDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KbDataInitializer.class);

    private final KbDao kbDao;

    /** 是否注入 POC 演示种子数据（默认为 false，保持知识库干净） */
    @Value("${app.kb.demo-seed:false}")
    private boolean demoSeedEnabled;

    public KbDataInitializer(KbDao kbDao) {
        this.kbDao = kbDao;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!demoSeedEnabled) {
            log.warn("知识库演示数据已禁用（app.kb.demo-seed=false），跳过注入");
            return;
        }
        if (kbDao.paperCount() > 0) {
            return;
        }

        String demoSource = "示例数据（POC 演示用，非真实文献）";

        // 论文 1：患病率研究
        kbDao.insertPaper(new Paper(null,
                "云南大理地区白族人群2型糖尿病患病率及影响因素分析",
                "白族", "糖尿病",
                "大理市3个乡镇20岁及以上白族常住居民2158人",
                "2019",
                "横断面调查显示白族人群2型糖尿病患病率为8.6%（标化后7.9%），高于同期全国成年人平均水平；"
                        + "患病率随年龄增长明显上升，50岁及以上人群达15.2%；"
                        + "知晓率52.3%、治疗率44.1%、控制率31.7%。",
                "横断面设计无法确定因果关系；样本集中于大理地区，外推至其他白族聚居区需谨慎。",
                demoSource));

        // 论文 2：危险因素研究
        kbDao.insertPaper(new Paper(null,
                "白族人群2型糖尿病危险因素的病例对照研究",
                "白族", "糖尿病",
                "白族2型糖尿病患者412例及社区对照412例",
                "2017",
                "超重/肥胖（OR=2.31）、糖尿病家族史（OR=2.86）、高血压（OR=1.74）与白族人群2型糖尿病显著相关；"
                        + "以精米为主食、体力活动不足者风险升高。",
                "病例来自医院，可能存在选择偏倚；生活方式信息依赖问卷回忆，存在回忆偏倚。",
                demoSource));

        // 论文 3：膳食模式研究
        kbDao.insertPaper(new Paper(null,
                "白族传统膳食模式与2型糖尿病发病风险的前瞻性队列研究",
                "白族", "糖尿病",
                "大理白族聚居村40-70岁常住居民1836人，平均随访4.2年",
                "2021",
                "传统\"乳扇-腌菜-精米\"膳食模式评分最高四分位组糖尿病发病风险较最低组升高47%；"
                        + "常摄入新鲜蔬菜与豆类者风险降低约23%；高盐腌制食品摄入频率与糖代谢异常相关。",
                "膳食评估采用食物频率问卷，存在测量误差；随访时间相对较短。",
                demoSource));

        // 论文 4：遗传易感性研究
        kbDao.insertPaper(new Paper(null,
                "TCF7L2基因多态性与云南白族人群2型糖尿病易感性的关联研究",
                "白族", "糖尿病",
                "无血缘关系白族个体1024人（病例512例、对照512例）",
                "2020",
                "TCF7L2 rs7903146 T等位基因在白族病例组频率显著高于对照组（OR=1.58）；"
                        + "该位点与空腹血糖及HbA1c水平相关，提示遗传易感性存在人群特异性。",
                "单一候选基因策略覆盖位点有限；基因-环境交互作用未深入分析。",
                demoSource));

        // 论文1 证据（患病情况）
        long p1 = kbDao.findPapers("白族", "糖尿病").get(0).id();
        kbDao.insertEvidence(new Evidence(null, p1, "prevalence",
                "白族人群2型糖尿病患病率为8.6%（标化后7.9%），其中男性9.3%、女性8.0%。"));
        kbDao.insertEvidence(new Evidence(null, p1, "prevalence",
                "50岁及以上年龄组患病率达15.2%，年龄与患病率呈显著正相关（P<0.01）。"));
        kbDao.insertEvidence(new Evidence(null, p1, "prevalence",
                "糖尿病患者中知晓率52.3%、治疗率44.1%、控制率31.7%，\"三率\"均偏低。"));
        kbDao.insertEvidence(new Evidence(null, p1, "overview",
                "研究基于分层随机抽样，覆盖大理3个乡镇的20岁及以上白族常住居民，结果对白族聚居区具有较好代表性。"));

        // 论文2 证据（危险因素）
        java.util.List<Paper> list = kbDao.findPapers("白族", "糖尿病");
        Paper p2 = list.get(1);
        kbDao.insertEvidence(new Evidence(null, p2.id(), "risk",
                "超重或肥胖者患2型糖尿病风险约为正常体重者的2.31倍（OR=2.31）。"));
        kbDao.insertEvidence(new Evidence(null, p2.id(), "risk",
                "有糖尿病家族史者患病风险为无家族史者的2.86倍（OR=2.86）。"));
        kbDao.insertEvidence(new Evidence(null, p2.id(), "risk",
                "合并高血压者糖尿病风险升高74%（OR=1.74）。"));
        kbDao.insertEvidence(new Evidence(null, p2.id(), "risk",
                "以精米为主食且体力活动不足者患病风险显著升高。"));

        // 论文3 证据（饮食与生活方式）
        Paper p3 = list.get(2);
        kbDao.insertEvidence(new Evidence(null, p3.id(), "diet",
                "传统\"乳扇-腌菜-精米\"膳食模式评分最高组的糖尿病发病风险较最低组升高47%（HR=1.47）。"));
        kbDao.insertEvidence(new Evidence(null, p3.id(), "diet",
                "常摄入新鲜蔬菜与豆类者发病风险降低约23%。"));
        kbDao.insertEvidence(new Evidence(null, p3.id(), "diet",
                "高盐腌制食品摄入频率与空腹血糖受损呈正相关。"));

        // 论文4 证据（遗传相关）
        Paper p4 = list.get(3);
        kbDao.insertEvidence(new Evidence(null, p4.id(), "genetics",
                "TCF7L2 rs7903146 T等位基因携带者的糖尿病风险升高58%（OR=1.58）。"));
        kbDao.insertEvidence(new Evidence(null, p4.id(), "genetics",
                "该位点变异与空腹血糖及HbA1c水平独立相关。"));
        kbDao.insertEvidence(new Evidence(null, p4.id(), "genetics",
                "白族人群中等位基因频率与汉族人群存在差异，提示遗传易感性具有人群特异性。"));

        log.info("知识库演示数据已初始化：4 篇论文 / 16 条证据（白族 + 糖尿病，示例数据）");
    }
}
