package com.jiegiser.llm.fileserver;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MinIO 客户端配置类
 * <p>
 * 根据 application.yml 中的配置创建 MinioClient Bean
 * </p>
 */
@Configuration
public class MinioConfig {

    /**
     * MinIO 服务端地址
     * <p>例如：http://localhost:9000</p>
     */
    @Value("${minio.url}")
    private String url;

    /**
     * MinIO 访问密钥（用户名）
     * <p>默认：minioadmin</p>
     */
    @Value("${minio.accessKey}")
    private String accessKey;

    /**
     * MinIO 秘密密钥（密码）
     * <p>默认：minioadmin</p>
     */
    @Value("${minio.secretKey}")
    private String secretKey;

    /**
     * 创建 MinioClient Bean
     * <p>
     * MinioClient 是 MinIO Java SDK 的核心客户端，用于：
     * <ul>
     *   <li>创建/删除 Bucket</li>
     *   <li>上传/下载/删除文件</li>
     *   <li>生成预签名 URL</li>
     *   <li>管理 Bucket 策略</li>
     * </ul>
     * </p>
     *
     * @return MinioClient 实例
     */
    @Bean
    public MinioClient minioClient() {
        return MinioClient.builder()
                .endpoint(url)
                .credentials(accessKey, secretKey)
                .build();
    }
}
