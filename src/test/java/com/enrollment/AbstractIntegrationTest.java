package com.enrollment;

import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

// 통합 테스트 기반 클래스 — 싱글턴 컨테이너 패턴으로 JVM 전체에서 컨테이너 1회만 기동
// @Container/@Testcontainers 대신 static initializer 사용 → 클래스 간 컨텍스트 캐시 포트 불일치 방지
// Java HttpURLConnection 은 PATCH 를 지원하지 않으므로 Apache HttpComponents 5.x 로 교체
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> postgres;

    static {
        postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("enrollment_test")
            .withUsername("test")
            .withPassword("test");
        postgres.start();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // 동시성 테스트에서 순차 락 대기 시 타임아웃 방지 — 느린 Docker 환경 고려
        registry.add("spring.jpa.properties.jakarta.persistence.lock.timeout", () -> "30000");
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @BeforeEach
    void configureHttpFactory() {
        // Java 기본 HttpURLConnection 은 PATCH 메서드를 지원하지 않음 — Apache HttpComponents 5.x 로 교체
        // maxConnPerRoute 50 — 동시성 테스트(10~15 스레드)에서 HTTP 연결 큐잉 방지
        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
        connectionManager.setMaxTotal(50);
        connectionManager.setDefaultMaxPerRoute(50);
        var httpClient = HttpClients.custom().setConnectionManager(connectionManager).build();
        restTemplate.getRestTemplate().setRequestFactory(new HttpComponentsClientHttpRequestFactory(httpClient));
    }
}
