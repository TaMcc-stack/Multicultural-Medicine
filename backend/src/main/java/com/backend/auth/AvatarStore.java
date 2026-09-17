package com.backend.auth;

import com.backend.common.BizException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 头像图片的存取。
 *
 * <p><b>为什么存盘而不是存进数据库：</b>动态模块将来要展示作者头像，届时任何列表接口
 * 只需带一个 URL；若把图片以 data URI 存进用户表，每个列表项都要驮着几十 KB 的 base64。</p>
 *
 * <p>文件名固定为 {@code {userId}.{ext}}：重新上传时先清掉该用户的旧文件，天然不会
 * 留下孤儿文件，也不需要额外的清理任务。前端靠 URL 上的 {@code ?v=} 破缓存。</p>
 */
@Component
public class AvatarStore {

    /** 单张头像大小上限 */
    private static final long MAX_BYTES = 2 * 1024 * 1024;
    /** 允许的图片类型（由文件头嗅探得出，不信客户端传的 Content-Type） */
    private static final List<String> ALLOWED = List.of("png", "jpg", "webp");

    private final Path dir;

    public AvatarStore(@Value("${app.auth.avatar-dir:data/avatars}") String avatarDir) {
        this.dir = Paths.get(avatarDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建头像目录: " + dir, e);
        }
    }

    /**
     * 保存头像，返回版本号（毫秒时间戳，供前端拼 {@code ?v=} 破缓存）。
     *
     * @throws BizException 文件为空 / 超限 / 不是受支持的图片格式
     */
    public long save(Long userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException("请选择要上传的图片");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new BizException("图片不能超过 2MB");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BizException("读取上传文件失败");
        }
        String ext = sniff(bytes);
        if (ext == null) {
            throw new BizException("只支持 PNG / JPG / WebP 格式的图片");
        }

        clear(userId);
        long version = System.currentTimeMillis();
        try {
            Files.write(dir.resolve(userId + "." + ext), bytes);
        } catch (IOException e) {
            throw new BizException("保存头像失败");
        }
        return version;
    }

    /** 读取某用户的头像；没有上传过则返回 empty */
    public Optional<StoredAvatar> load(Long userId) {
        try (Stream<Path> files = Files.list(dir)) {
            Optional<Path> hit = files
                    .filter(p -> p.getFileName().toString().startsWith(userId + "."))
                    .findFirst();
            if (hit.isEmpty()) {
                return Optional.empty();
            }
            Path p = hit.get();
            String name = p.getFileName().toString();
            String ext = name.substring(name.lastIndexOf('.') + 1);
            return Optional.of(new StoredAvatar(Files.readAllBytes(p), contentType(ext)));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** 删除该用户已存在的头像文件（不区分扩展名） */
    private void clear(Long userId) {
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().startsWith(userId + "."))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // 删不掉就留着，save 会写新扩展名的文件，load 取到哪个都能用
                        }
                    });
        } catch (IOException ignored) {
            // 目录读取失败不阻断上传
        }
    }

    /**
     * 按文件头判断真实类型。
     * 不信客户端的 Content-Type —— 它只是一个可以随便填的字符串，
     * 拿它决定存什么扩展名、回什么 Content-Type，等于把上传类型交给调用方决定。
     */
    private static String sniff(byte[] b) {
        if (b.length >= 4 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "png";
        }
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "jpg";
        }
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "webp";
        }
        return null;
    }

    private static String contentType(String ext) {
        return switch (ext) {
            case "png" -> "image/png";
            case "webp" -> "image/webp";
            default -> "image/jpeg";
        };
    }

    /** 读出的头像内容 */
    public record StoredAvatar(byte[] bytes, String contentType) {
    }
}
