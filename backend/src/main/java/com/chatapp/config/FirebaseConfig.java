package com.chatapp.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Firebase Admin SDK initialization.
 *
 * Lazy init: nếu FIREBASE_CREDENTIALS_PATH không set hoặc file không tồn tại,
 * chỉ log WARN và bỏ qua — OAuth endpoint sẽ không hoạt động nhưng app vẫn start bình thường.
 * Hữu ích cho môi trường dev chỉ test password auth.
 *
 * Bean firebaseAuth được expose với @Bean — nếu init fail thì bean không được tạo
 * và AuthService nhận null qua @Autowired(required=false).
 */
@Slf4j
@Configuration
public class FirebaseConfig {

    @Value("${FIREBASE_CREDENTIALS_PATH:}")
    private String credentialsPath;

    private boolean initialized = false;

    @PostConstruct
    public void initializeFirebase() {
        if (credentialsPath == null || credentialsPath.isBlank()) {
            log.warn("[FirebaseConfig] FIREBASE_CREDENTIALS_PATH not set — OAuth endpoint will be unavailable");
            return;
        }

        try {
            Path path = Path.of(credentialsPath);
            if (!Files.exists(path)) {
                log.warn("[FirebaseConfig] Firebase credentials file not found: {} — OAuth endpoint will be unavailable",
                        credentialsPath);
                return;
            }

            if (FirebaseApp.getApps().isEmpty()) {
                try (FileInputStream serviceAccount = new FileInputStream(path.toFile())) {
                    FirebaseOptions options = FirebaseOptions.builder()
                            .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                            .build();
                    FirebaseApp.initializeApp(options);
                    log.info("[FirebaseConfig] Firebase Admin SDK initialized successfully");
                }
            } else {
                log.debug("[FirebaseConfig] Firebase already initialized — skipping");
            }
            initialized = true;
        } catch (IOException e) {
            log.error("[FirebaseConfig] Failed to initialize Firebase Admin SDK: {}", e.getMessage());
        }
    }

    /**
     * Expose FirebaseAuth instance làm Spring bean — chỉ khi đã init thành công.
     * Trả null nếu Firebase chưa init → AuthService nhận null qua @Autowired(required=false).
     *
     * Lưu ý: @Bean trả null được Spring bỏ qua (không tạo bean), các nơi inject sẽ nhận null
     * nếu dùng @Autowired(required=false).
     */
    @Bean
    public FirebaseAuth firebaseAuth() {
        if (!initialized) {
            return null;
        }
        return FirebaseAuth.getInstance();
    }
}
