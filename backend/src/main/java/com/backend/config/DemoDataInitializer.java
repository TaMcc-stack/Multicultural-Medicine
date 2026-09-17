package com.backend.config;

import com.backend.auth.dao.UserDao;
import com.backend.auth.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 演示数据初始化：应用启动时若无 demo 账号则自动创建
 *
 * <p>注意：中文默认值写在 Java 源码里而不是 application.properties，
 * 因为 .properties 文件按 ISO-8859-1 读取，中文会变成双重编码乱码
 * （Java 源码以 UTF-8 编译，中文常量是安全的）。</p>
 */
@Configuration
public class DemoDataInitializer {

    private static final Logger log = LoggerFactory.getLogger(DemoDataInitializer.class);

    /** 演示账号昵称（中文放在 Java 源码中，避免 properties 编码问题） */
    private static final String DEFAULT_DEMO_NICKNAME = "演示用户";

    @Bean
    ApplicationRunner initDemoUser(UserDao userDao,
                                   @Value("${app.auth.demo-username:demo}") String username,
                                   @Value("${app.auth.demo-password:123456}") String password,
                                   @Value("${app.auth.demo-nickname:}") String nickname) {
        return args -> {
            if (userDao.findByUsername(username).isEmpty()) {
                User user = new User();
                user.setUsername(username);
                user.setPassword(new BCryptPasswordEncoder().encode(password));
                user.setNickname(nickname.isBlank() ? DEFAULT_DEMO_NICKNAME : nickname);
                userDao.insert(user);
                log.info("已创建演示账号：{}（密码：{}）", username, password);
            }
        };
    }
}
