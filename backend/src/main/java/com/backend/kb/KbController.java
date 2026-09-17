package com.backend.kb;

import com.backend.auth.AuthController;
import com.backend.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库接口（用户上传文档：上传 / 查询 / 删除 / 查看全文 / 下载原文）。
 * 数据由 Spring Boot 持久化（H2）+ 文件系统存储，前端经此对接。
 *
 * <p><b>鉴权</b>：除下面两个只读文件端点外，全部要求登录（见 WebConfig 的拦截规则）。
 * 来源等级（official / web_crawl / user_upload）与审核状态由后端按上传者身份裁决，
 * <b>不接受前端直接指定 official</b>。</p>
 */
@RestController
@RequestMapping("/api/kb")
public class KbController {

    private final KbService kbService;

    public KbController(KbService kbService) {
        this.kbService = kbService;
    }

    /** 上传文档：multipart/form-data（file + 可选 title/category/source/author/publishYear/summary/evidenceJson + 来源维度）
     *  - evidenceJson 由前端在「解析→确认」流程中回传（用户已核对的结构化数据），提供时直接采用并跳过 LLM 重复抽取
     *  - 未提供 evidenceJson 时，后端自动抽取（含大模型辅助）
     *  - sourceLevel 只是「申请」，非官方账号会被后端降级为 user_upload 并进入待审核 */
    @PostMapping("/upload")
    public ApiResponse<KbDocDto> upload(
            @RequestAttribute(AuthController.ATTR_USER_ID) Long userId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "source", required = false) String source,
            @RequestParam(value = "author", required = false) String author,
            @RequestParam(value = "publishYear", required = false) Integer publishYear,
            @RequestParam(value = "summary", required = false) String summary,
            @RequestParam(value = "articleSummary", required = false) String articleSummary,
            @RequestParam(value = "evidenceJson", required = false) String evidenceJson,
            @RequestParam(value = "sourceLevel", required = false) String sourceLevel,
            @RequestParam(value = "sourceOrg", required = false) String sourceOrg,
            @RequestParam(value = "sourceUrl", required = false) String sourceUrl,
            // 文献补录工作台带来的缺口 id：非空则强制进待审核，审核通过且索引成功后自动结掉该缺口
            @RequestParam(value = "gapId", required = false) Long gapId) {
        try {
            KbService.SourceInfo src = (gapId == null)
                    ? KbService.SourceInfo.of(userId, sourceLevel, sourceOrg, sourceUrl)
                    : KbService.SourceInfo.forGap(userId, sourceLevel, sourceOrg, sourceUrl, gapId);
            KbDocDto dto = kbService.upload(file, title, category, source, author, publishYear,
                    summary, articleSummary, evidenceJson, src);
            return ApiResponse.ok(dto);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error(500, "上传失败：" + e.getMessage());
        }
    }

    /**
     * 导入外部内容：抓白名单内的网页，或接收管理员**粘贴**的正文，作为一份资料入库。
     *
     * <p>两种取数方式**结果完全一致**：都标记 web_crawl、都进待审核。区别只在正文从哪来——
     * 需要登录才能看的站点（如 SinoMed，检索要验证码，服务端抓不到）走粘贴。</p>
     *
     * <p>只有官方账号可用——这是内容录入工具，不是普通用户功能。</p>
     */
    @PostMapping("/import")
    public ApiResponse<KbDocDto> importExternal(
            @RequestAttribute(AuthController.ATTR_USER_ID) Long userId,
            @RequestBody(required = false) Map<String, Object> body) {
        if (!kbService.isOfficial(userId)) {
            return ApiResponse.error(403, "只有官方账号可以导入外部内容");
        }
        String url = body != null ? (String) body.get("url") : null;
        String text = body != null ? (String) body.get("text") : null;
        String title = body != null ? (String) body.get("title") : null;
        // 文献补录工作台会带上要补的缺口 id（JSON number 反序列化成 Integer，统一转 Long）
        Long gapId = null;
        Object rawGap = body != null ? body.get("gapId") : null;
        if (rawGap instanceof Number n) gapId = n.longValue();
        try {
            KbDocDto dto = (text != null && !text.isBlank())
                    // 粘贴模式：url 只作溯源记录，服务端不会去访问它
                    ? kbService.importText(text, title, url, userId, gapId)
                    // 抓取模式：先校验白名单再发请求
                    : kbService.importFromUrl(url, userId, gapId);
            return ApiResponse.ok(dto);
        } catch (IllegalArgumentException e) {
            // 参数 / 白名单问题：消息就是写给管理员看的，原样透出
            return ApiResponse.error(400, e.getMessage());
        } catch (IllegalStateException e) {
            // 抓取失败（目标站拒绝、跳转越界、抓取服务不在线）
            return ApiResponse.error(502, e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error(500, "导入失败：" + e.getMessage());
        }
    }

    /**
     * 文献补录工作台：把若干<b>片段</b>合成**一份**整合文档，进待审核。
     *
     * <p>与 {@code /import} 的分工是「内容从哪来」：那边收的是一篇完整原文（抓的或粘的），
     * 这里收的是多篇文献的零散片段（拿不到全文时逐段攒起来）。落库、审核、索引同步
     * 完全复用同一条路。</p>
     *
     * <p>文档的 Markdown / Word 由服务端渲染（见 {@code SupplyRenderer}），
     * 前端传给 {@code /supply/preview} 看到的就是存进来的那份，避免两处渲染漂移。</p>
     *
     * <p>只有官方账号可用——这是内容录入工具，不是普通用户功能。</p>
     */
    @PostMapping("/supply")
    public ApiResponse<KbDocDto> supply(
            @RequestAttribute(AuthController.ATTR_USER_ID) Long userId,
            @Valid @RequestBody SupplyRequest req) {
        if (!kbService.isOfficial(userId)) {
            return ApiResponse.error(403, "只有官方账号可以补录文献");
        }
        try {
            return ApiResponse.ok(kbService.supply(req, userId));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error(500, "补录失败：" + e.getMessage());
        }
    }

    /**
     * 「整理成文档」：**只渲染、不落库**，把合成后的文档交给管理员先看一眼。
     *
     * <p>为什么要单独一个预览口而不是前端自己拼 Markdown：整合文档的形态是后端定的，
     * 前端再抄一份模板就变成「同一份措辞两处维护」，改一处忘另一处的结果是
     * 管理员在界面上看到的和真正进库的不一样——这种差异不会报错，只会事后才发现。</p>
     *
     * <p>返回 Markdown（预览/复制/.md 下载）与 Word 版 HTML（.doc 下载）两种形态，
     * 内容与 {@code /supply} 落库的那份逐字一致。</p>
     */
    @PostMapping("/supply/preview")
    public ApiResponse<Map<String, Object>> supplyPreview(
            @RequestAttribute(AuthController.ATTR_USER_ID) Long userId,
            @RequestBody(required = false) SupplyRequest req) {
        if (!kbService.isOfficial(userId)) {
            return ApiResponse.error(403, "只有官方账号可以补录文献");
        }
        if (req == null || req.ethnicity() == null || req.disease() == null) {
            return ApiResponse.error(400, "缺少民族或疾病");
        }
        try {
            return ApiResponse.ok(kbService.supplyPreview(req));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error(500, "整理失败：" + e.getMessage());
        }
    }

    /**
     * 文献补录工作台：**只取正文**（上传文件 或 抓白名单链接），不进库、不跑 LLM。
     *
     * <p>工作台要先拿到正文才能抽数据行。抓取必须过服务端白名单，所以不能由前端直接去调
     * AI 服务的抓取接口——那条路径没有白名单，等于把 SSRF 的闸门敞开。</p>
     */
    @PostMapping("/supply-source")
    public ApiResponse<Map<String, Object>> supplySource(
            @RequestAttribute(AuthController.ATTR_USER_ID) Long userId,
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "url", required = false) String url) {
        if (!kbService.isOfficial(userId)) {
            return ApiResponse.error(403, "只有官方账号可以使用文献补录");
        }
        try {
            return ApiResponse.ok(kbService.supplySource(file, url));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        } catch (IllegalStateException e) {
            // 抓取失败（目标站拒绝、跳转越界、抓取服务不在线）
            return ApiResponse.error(502, e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error(500, "取正文失败：" + e.getMessage());
        }
    }

    /** 解析（仅抽取、不落库）：返回自动识别的基本信息与结构化证据，供前端展示与用户确认/编辑 */
    @PostMapping("/parse")
    public ApiResponse<KbParsePreview> parse(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "source", required = false) String source,
            @RequestParam(value = "author", required = false) String author,
            @RequestParam(value = "publishYear", required = false) Integer publishYear) {
        try {
            return ApiResponse.ok(kbService.parse(file, title, category, source, author, publishYear));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error(500, "解析失败：" + e.getMessage());
        }
    }

    /** 文档列表（不含全文）；可按来源等级 / 审核状态筛选（后者仅官方账号有效） */
    @GetMapping("/docs")
    public ApiResponse<List<KbDocDto>> listDocs(
            @RequestAttribute(AuthController.ATTR_USER_ID) Long userId,
            @RequestParam(value = "sourceLevel", required = false) String sourceLevel,
            @RequestParam(value = "reviewStatus", required = false) String reviewStatus) {
        return ApiResponse.ok(kbService.list(userId, sourceLevel, reviewStatus));
    }

    /**
     * 审核用户提交的资料（通过 / 驳回）——仅管理员账号可调用。
     *
     * <p>通过时需指定归入的一级分类（official=官方权威资料 / web_crawl=网页抓取补充），
     * 通过后才推入 AI 检索索引；驳回会从索引移除。文件本身不删，便于追溯与反悔。</p>
     */
    @PostMapping("/docs/{id}/review")
    public ApiResponse<Map<String, Object>> review(
            @RequestAttribute(AuthController.ATTR_USER_ID) Long userId,
            @PathVariable("id") String id,
            @RequestBody(required = false) Map<String, Object> body) {
        if (!kbService.isOfficial(userId)) {
            return ApiResponse.error(403, "只有管理员账号可以审核资料");
        }
        String decision = body != null ? (String) body.get("decision") : null;
        String targetLevel = body != null ? (String) body.get("sourceLevel") : null;
        try {
            kbService.review(id, decision, targetLevel);
            Map<String, Object> r = new HashMap<>();
            r.put("ok", true);
            return ApiResponse.ok(r);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        } catch (IllegalStateException e) {
            return ApiResponse.error(404, e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error(500, "审核失败：" + e.getMessage());
        }
    }

    /**
     * 改属知识库分区（整合资料库 / 原始文献库）。
     *
     * <p>入库时的分区按扩展名自动判定（pdf → 原始文献库，其余 → 整合资料库），判错了
     * 管理员在这里手工改。改完会**重新同步检索索引**（数秒），否则界面上搬过去了、
     * 检索却还按老分区走。</p>
     */
    @PostMapping("/docs/{id}/partition")
    public ApiResponse<Map<String, Object>> movePartition(
            @RequestAttribute(AuthController.ATTR_USER_ID) Long userId,
            @PathVariable("id") String id,
            @RequestBody(required = false) Map<String, Object> body) {
        if (!kbService.isOfficial(userId)) {
            return ApiResponse.error(403, "只有管理员账号可以调整知识库分区");
        }
        String partition = body != null ? (String) body.get("partition") : null;
        try {
            kbService.movePartition(id, partition);
            Map<String, Object> r = new HashMap<>();
            r.put("ok", true);
            return ApiResponse.ok(r);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        } catch (IllegalStateException e) {
            return ApiResponse.error(404, e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error(500, "调整分区失败：" + e.getMessage());
        }
    }

    /**
     * 用当前解析规则重新解析一份已入库的资料（管理员）。
     *
     * <p>正文是入库那一刻存下来的，解析规则升级（比如 docx 转 Markdown 的排版变好）不会
     * 回溯到老文档，用这个原地重跑一遍——重新上传会多出一份同名副本。</p>
     */
    @PostMapping("/docs/{id}/reparse")
    public ApiResponse<KbDocDto> reparse(
            @RequestAttribute(AuthController.ATTR_USER_ID) Long userId,
            @PathVariable("id") String id) {
        if (!kbService.isOfficial(userId)) {
            return ApiResponse.error(403, "只有管理员账号可以重新解析资料");
        }
        try {
            return ApiResponse.ok(kbService.reparse(id));
        } catch (IllegalStateException e) {
            return ApiResponse.error(404, e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error(500, "重新解析失败：" + e.getMessage());
        }
    }

    /** 文档后台处理状态（上传后异步总结/证据/索引的进度，前端据此渲染进度条并轮询） */
    @GetMapping("/docs/{id}/status")
    public ApiResponse<Map<String, Object>> status(@PathVariable("id") String id) {
        return ApiResponse.ok(kbService.status(id));
    }

    /** 文档详情（含全文 content） */
    @GetMapping("/docs/{id}")
    public ApiResponse<KbDocDto> getDoc(@PathVariable("id") String id) {
        try {
            return ApiResponse.ok(kbService.get(id));
        } catch (IllegalStateException e) {
            return ApiResponse.error(404, e.getMessage());
        }
    }

    /** 更新文档的标题 / 分类 / 文章总结 / 结构化证据（管理员审核编辑、修复编码乱码或校对后回填；JSON 体走 Spring Jackson 的 UTF-8 解码，避免 multipart 文本字段乱码） */
    @PutMapping("/docs/{id}/meta")
    public ApiResponse<Map<String, Object>> updateMeta(
            @PathVariable("id") String id,
            @RequestBody(required = false) Map<String, Object> body) {
        try {
            String title = body != null ? (String) body.get("title") : null;
            String category = body != null ? (String) body.get("category") : null;
            String articleSummary = body != null ? (String) body.get("articleSummary") : null;
            String evidenceJson = body != null ? (String) body.get("evidenceJson") : null;
            kbService.updateMeta(id, title, category, articleSummary, evidenceJson);
            Map<String, Object> r = new HashMap<>();
            r.put("ok", true);
            return ApiResponse.ok(r);
        } catch (IllegalStateException e) {
            return ApiResponse.error(404, e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error(500, "更新失败：" + e.getMessage());
        }
    }

    /** PDF 某页的原页图（渲染为 PNG），供阅读器在每页正文后显示原版页面（含矢量图）。 */
    @GetMapping("/docs/{id}/page/{page}")
    public ResponseEntity<byte[]> pageImage(@PathVariable("id") String id, @PathVariable("page") int page) {
        try {
            byte[] png = kbService.renderPage(id, page);
            if (png == null) return ResponseEntity.notFound().build();
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_PNG)
                    .header(HttpHeaders.CACHE_CONTROL, "max-age=86400")
                    .body(png);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    /** 删除文档（文件 + 元数据）；非官方账号只能删自己上传的 */
    @DeleteMapping("/docs/{id}")
    public ApiResponse<Map<String, Object>> deleteDoc(
            @RequestAttribute(AuthController.ATTR_USER_ID) Long userId,
            @PathVariable("id") String id) {
        try {
            // 归属校验放在 Controller 而不是 Service：Service.delete 只做删除本身，不做归属判断。
            KbDocDto doc = kbService.get(id);
            if (!kbService.isOfficial(userId) && !userId.equals(doc.getUserId())) {
                return ApiResponse.error(403, "只能删除自己上传的资料");
            }
            kbService.delete(id);
            Map<String, Object> r = new HashMap<>();
            r.put("ok", true);
            return ApiResponse.ok(r);
        } catch (IllegalStateException e) {
            return ApiResponse.error(404, e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error(500, "删除失败：" + e.getMessage());
        }
    }

    /** 原始文件（PDF 浏览器内联预览，Word 触发下载） */
    @GetMapping("/files/{id}")
    public ResponseEntity<Resource> file(@PathVariable("id") String id) {
        try {
            Path p = kbService.filePath(id);
            FileSystemResource res = new FileSystemResource(p);
            String fileName = p.getFileName().toString();
            String inlineName = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
            String mime = Files.probeContentType(p);
            if (mime == null) mime = "application/octet-stream";
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "inline; filename=\"" + inlineName + "\"; filename*=UTF-8''" + inlineName)
                    .contentType(MediaType.parseMediaType(mime))
                    .body(res);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }
}
