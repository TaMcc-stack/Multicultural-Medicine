package com.backend.auth;

import com.backend.auth.dao.UserDao;
import com.backend.auth.dto.LoginRequest;
import com.backend.auth.dto.LoginResponse;
import com.backend.auth.dto.RegisterRequest;
import com.backend.auth.dto.UpdateProfileRequest;
import com.backend.auth.dto.UserInfo;
import com.backend.auth.entity.User;
import com.backend.common.BizException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 登录 / 注册业务逻辑
 */
@Service
public class AuthService {

    /** 个人资料的长度上限（与前端输入框的 maxlength 保持一致） */
    private static final int NICKNAME_MAX = 20;
    private static final int BIO_MAX = 200;

    /** 可用头像配色 key；与前端 AVATAR_PRESETS 一一对应，两端都改才生效 */
    private static final String DEFAULT_AVATAR = "sage";
    private static final java.util.Set<String> AVATAR_KEYS = java.util.Set.of(
            "sage", "clay", "amber", "plum", "sky", "moss", "rust", "ink");
    /** 自定义头像的存储值前缀：upload:{版本号}，版本号用于破缓存 */
    public static final String AVATAR_UPLOAD_PREFIX = "upload:";

    private final UserDao userDao;
    private final AvatarStore avatarStore;
    private final TokenManager tokenManager;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AuthService(UserDao userDao, TokenManager tokenManager, AvatarStore avatarStore) {
        this.userDao = userDao;
        this.tokenManager = tokenManager;
        this.avatarStore = avatarStore;
    }

    /** 登录：校验用户名密码，签发 token */
    public LoginResponse login(LoginRequest request) {
        User user = userDao.findByUsername(request.getUsername().trim())
                .orElseThrow(() -> new BizException("用户名或密码错误"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new BizException("用户名或密码错误");
        }

        String token = tokenManager.issue(user.getId());
        return new LoginResponse(token, toUserInfo(user));
    }

    /** 注册：用户名唯一，密码 BCrypt 加密后入库，注册成功后自动登录 */
    public LoginResponse register(RegisterRequest request) {
        String username = request.getUsername().trim();
        if (userDao.findByUsername(username).isPresent()) {
            throw new BizException("该用户名已被注册");
        }

        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        String nickname = (request.getNickname() == null || request.getNickname().isBlank())
                ? username : request.getNickname().trim();
        user.setNickname(nickname);
        userDao.insert(user);

        String token = tokenManager.issue(user.getId());
        return new LoginResponse(token, toUserInfo(user));
    }

    /** 注销 */
    public void logout(String token) {
        tokenManager.revoke(token);
    }

    /** 查询当前登录用户信息 */
    public UserInfo getUserInfo(Long userId) {
        User user = requireUser(userId);
        return toUserInfo(user);
    }

    /**
     * 更新个人资料（昵称 / 简介 / 头像配色）。
     * 用户名与密码不可通过此接口修改。
     */
    public UserInfo updateProfile(Long userId, UpdateProfileRequest request) {
        User user = requireUser(userId);

        String nickname = request.getNickname() == null ? "" : request.getNickname().trim();
        if (nickname.isEmpty()) {
            throw new BizException("昵称不能为空");
        }
        if (nickname.length() > NICKNAME_MAX) {
            throw new BizException("昵称不能超过 " + NICKNAME_MAX + " 个字");
        }

        String bio = request.getBio() == null ? "" : request.getBio().trim();
        if (bio.length() > BIO_MAX) {
            throw new BizException("个人简介不能超过 " + BIO_MAX + " 个字");
        }

        // 头像只接受受控的配色 key：这里存的是「渲染方式」而不是用户数据，
        // 放开会让前端拿到任意字符串去拼样式。
        String avatar = request.getAvatar() == null ? "" : request.getAvatar().trim();
        if (avatar.startsWith(AVATAR_UPLOAD_PREFIX)) {
            // 自定义头像只能由上传接口写入。这里收到 upload: 说明前端把当前值原样回传了
            // （改昵称/简介时都会带上），保持原值——否则一次改昵称就会把上传的头像冲掉。
            avatar = user.getAvatar();
        } else if (!AVATAR_KEYS.contains(avatar)) {
            avatar = DEFAULT_AVATAR;
        }

        userDao.updateProfile(userId, nickname, bio, avatar);
        user.setNickname(nickname);
        user.setBio(bio);
        user.setAvatar(avatar);
        return toUserInfo(user);
    }

    /**
     * 保存自定义头像。落盘后把用户表的 avatar 置为 {@code upload:{版本号}}，
     * 版本号用于前端拼 {@code ?v=} 破浏览器缓存。
     */
    public UserInfo saveAvatar(Long userId, MultipartFile file) {
        User user = requireUser(userId);
        long version = avatarStore.save(userId, file);
        String avatar = AVATAR_UPLOAD_PREFIX + version;
        userDao.updateAvatar(userId, avatar);
        user.setAvatar(avatar);
        return toUserInfo(user);
    }

    /** 读取某用户的自定义头像；没上传过返回 empty（前端会退回「昵称首字 + 配色」） */
    public java.util.Optional<AvatarStore.StoredAvatar> loadAvatar(Long userId) {
        return avatarStore.load(userId);
    }

    private User requireUser(Long userId) {
        return userDao.findById(userId)
                .orElseThrow(() -> new BizException(
                        com.backend.common.ApiResponse.CODE_UNAUTHORIZED, "登录状态已失效，请重新登录"));
    }

    private UserInfo toUserInfo(User user) {
        String nickname = (user.getNickname() == null || user.getNickname().isBlank())
                ? user.getUsername() : user.getNickname();
        String avatar = (user.getAvatar() == null || user.getAvatar().isBlank())
                ? DEFAULT_AVATAR : user.getAvatar();
        String bio = user.getBio() == null ? "" : user.getBio();
        return new UserInfo(user.getId(), user.getUsername(), nickname, avatar, bio);
    }
}
