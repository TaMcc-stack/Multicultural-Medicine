"""
网页正文抓取与清洗（供知识库「从链接导入」使用）。

**职责边界**：这里只做「给一个 URL，拿回干净正文」。
白名单校验**不在这里**——它在 Java 侧、且在调用本服务**之前**完成（见 KbService.importFromUrl）。
白名单是业务规则，放在最外层少一条绕过路径。这里额外拦一层内网地址，只是纵深防御。

**为什么返回 finalUrl**：跟随重定向是必须的（http→https、短链跳转都要用），
但「白名单站点 302 到内网地址」就能绕过白名单。所以抓完把最终地址回传，
由 Java 侧**再校验一次**——重定向到白名单外的一律拒收。
"""

import ipaddress
import logging
import re
from urllib.parse import urlparse

import httpx
import trafilatura
from starlette.concurrency import run_in_threadpool

logger = logging.getLogger(__name__)

# 有些站点对默认 UA 直接 403，伪装成普通浏览器
_UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
       "(KHTML, like Gecko) Chrome/124.0 Safari/537.36")

# 只用来判断「提取彻底失败」，**不是**「这是不是文章页」的判据。
# 实测过：长度区分不了文章页和首页——www.people.com.cn 首页能提取出 1596 字，
# 比一篇真文章（约 1000 字）还长；trafilatura 的 pagetype 在本版本恒为 None，
# date/title 也不区分。所以「抓对了没有」交给**管理员在预览里判断**（见前端两阶段导入），
# 这里只挡住「一个字都没抓到」。
_MIN_CHARS = 100
# 入库上限：防止把一整个站点塞进知识库
_MAX_CHARS = 200_000

# trafilatura 会把页面上的按钮/工具条渲染成 markdown 强调（`*订阅*`、`*已收藏*`、`*小字号*`）。
# 这些不是正文却会跟着被切片和向量化，所以清掉。
# 用**显式词表**而不是「删掉所有短强调」——后者会误伤正文里真正的加粗词。
_UI_WORDS = {
    "订阅", "已订阅", "收藏", "已收藏", "点赞", "已赞", "分享", "评论",
    "打印", "小字号", "中字号", "大字号", "返回顶部", "扫码", "手机看",
    "放大", "缩小", "关闭", "更多", "展开", "收起", "复制", "纠错",
}
_EMPHASIS_RE = re.compile(r"\*([^*\n]{1,6})\*")


def _strip_ui_noise(text: str) -> str:
    """去掉 trafilatura 留下的 UI 按钮文字，并压掉多余空白/空行。"""
    text = _EMPHASIS_RE.sub(
        lambda m: "" if m.group(1).strip() in _UI_WORDS else m.group(0), text
    )
    # 连续空行压成一个（按钮被删后会留下成片的空行）
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()


class ArticleFetchError(Exception):
    """抓取失败。消息直接展示给管理员，所以写成人话而不是异常名。"""


def _assert_public_url(url: str) -> None:
    """
    纵深防御：拒绝非 http(s) 与非公网地址。

    只判**字面量 IP**，不做 DNS 解析——解析会引入 DNS 重绑定面的问题，
    真正的防线是 Java 侧的白名单。
    """
    p = urlparse(url)
    if p.scheme not in ("http", "https"):
        raise ArticleFetchError("只支持 http / https 链接")
    host = p.hostname
    if not host:
        raise ArticleFetchError("链接缺少主机名")
    try:
        ip = ipaddress.ip_address(host)
    except ValueError:
        return  # 是域名，交给白名单判断
    if ip.is_private or ip.is_loopback or ip.is_link_local or ip.is_reserved:
        raise ArticleFetchError("不允许抓取内网地址")


async def fetch_article(url: str, *, timeout: float = 20.0) -> dict:
    """
    抓取网页并提取正文。

    成功返回 {url, finalUrl, title, siteName, publishedAt, text, chars, truncated}；
    失败一律抛 ArticleFetchError（消息面向管理员）。
    """
    _assert_public_url(url)

    try:
        async with httpx.AsyncClient(
            follow_redirects=True,
            timeout=timeout,
            headers={"User-Agent": _UA, "Accept-Language": "zh-CN,zh;q=0.9"},
        ) as client:
            resp = await client.get(url)
    except httpx.HTTPError as e:
        raise ArticleFetchError(f"抓取失败：{e.__class__.__name__}") from e

    if resp.status_code != 200:
        raise ArticleFetchError(f"目标站点返回 {resp.status_code}")

    ctype = (resp.headers.get("content-type") or "").lower()
    if "html" not in ctype and "xml" not in ctype:
        raise ArticleFetchError("这个链接不是网页（可能是 PDF / 图片），请改用「上传文件」")

    html = resp.text

    # trafilatura 是同步的 CPU 活。放进线程池——不要在 async 路径上直接调用，
    # 否则一次抓取会把整个事件循环挡住（understanding.py 里就踩过这个）。
    # output_format=markdown 而不是纯文本：医学数据常在表格里，markdown 能保住表格结构，
    # 下游的 _md_table_to_text 正好能把它转成可读形式。
    text = await run_in_threadpool(
        trafilatura.extract, html,
        output_format="markdown", include_comments=False, include_tables=True,
    )
    meta = await run_in_threadpool(trafilatura.extract_metadata, html)

    text = _strip_ui_noise(text or "")
    if len(text) < _MIN_CHARS:
        raise ArticleFetchError(
            "没能从这个页面提取到正文（可能整页都是导航和脚本）。"
            "如果这是需要登录才能看的页面，请改用「粘贴正文」。"
        )

    truncated = len(text) > _MAX_CHARS
    if truncated:
        text = text[:_MAX_CHARS]
        logger.warning("抓取内容超长已截断：%s", resp.url)

    return {
        "url": url,
        "finalUrl": str(resp.url),
        # title/siteName 可能为空；siteName 还可能带「版权所有」这类噪音，
        # Java 侧会用白名单里配置的机构名覆盖它
        "title": (meta.title if meta else None) or "",
        "siteName": (meta.sitename if meta else None) or None,
        "publishedAt": (meta.date if meta else None) or None,
        "text": text,
        "chars": len(text),
        "truncated": truncated,
    }
