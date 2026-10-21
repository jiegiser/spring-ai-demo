package com.jiegiser.llm.mcp.service;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;

import javax.net.ssl.*;
import java.io.FileInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;

/**
 * HTTPS MCP 客户端示例（演示两种 SSL 证书校验模式）
 * <p>
 * 功能说明：
 * 当 MCP Server 部署在 HTTPS（TLS）环境时，客户端必须处理 SSL 证书校验。
 * 本类演示两种处理方式：
 * <p>
 * 1. createInsecureHttpsClient：不校验证书（信任所有证书）
 * - 适合：本地开发、内网测试（自签名证书环境）
 * - 风险：中间人攻击（MITM），生产环境禁用！
 * <p>
 * 2. createSecureHttpsClientPoc：加载 CA 证书校验（标准做法）
 * - 适合：生产环境
 * - 原理：把服务端证书（或其 CA 证书）导入本地信任库，握手时验证
 * <p>
 * HTTPS 握手背景知识：
 * - Java HttpClient 默认会校验服务端证书：
 * ① 证书是否由受信任的 CA 签发
 * ② 证书是否过期
 * ③ 证书的域名（CN/SAN）是否与访问的 host 一致
 * - 自签名证书（如本地测试用 openssl 生成）不满足条件 ①，默认握手直接失败
 * - 所以本地测试 HTTPS MCP Server 时，要么信任所有证书，要么手动导入证书
 * <p>
 * 传输层组成：
 * HttpClientSseClientTransport
 * ├── 自定义 HttpClient（配置 SSL 上下文、超时）
 * ├── 自定义 HttpRequest 模板（配置 Authorization 请求头）
 * └── SSE 端点路径（如 /sse）
 */
public class HttpsMcpService {

    /**
     * 创建"不校验证书"的 HTTPS MCP 客户端（仅限开发/测试！）
     * <p>
     * 原理：
     * 自定义一个 X509TrustManager，它的三个校验方法全是空实现，
     * 相当于告诉 JVM："任何证书我都信任" → 握手永远成功。
     * <p>
     * 三个校验方法的作用：
     * - checkClientTrusted：校验客户端证书（双向 TLS 时用，这里忽略）
     * - checkServerTrusted：校验服务端证书（关键校验点，这里被跳过）
     * - getAcceptedIssuers：返回信任的 CA 列表（返回空数组）
     * <p>
     * 同时禁用主机名验证：
     * setEndpointIdentificationAlgorithm(null) → 不校验证书域名与 host 是否一致
     * （否则即使信任了证书，域名不匹配还是会失败）
     * <p>
     * 安全警告：
     * ⚠️ 此方式等于关闭了 HTTPS 的身份验证，数据仍加密传输，
     * 但无法确认对方身份，可能被中间人攻击，生产环境绝对不能用！
     *
     * @param baseUrl  MCP Server 地址（如 https://127.0.0.1:8443）
     * @param endpoint SSE 端点路径（如 /sse）
     */
    public static void createInsecureHttpsClient(String baseUrl, String endpoint) {
        try {
            // 1. 创建一个信任所有证书的 TrustManager
            //    匿名实现：所有校验方法为空 = 信任一切证书
            TrustManager[] trustAllCerts = new TrustManager[]{new X509TrustManager() {
                /** 返回信任的 CA 证书列表（空 = 不校验颁发者） */
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }

                /** 校验客户端证书：空实现 = 跳过校验 */
                public void checkClientTrusted(X509Certificate[] certs, String authType) {
                }

                /** 校验服务端证书：空实现 = 跳过校验（核心跳过点） */
                public void checkServerTrusted(X509Certificate[] certs, String authType) {
                }
            }};

            // 2. 初始化 SSL 上下文，绕过校验
            //    TLS 协议 + 信任所有证书的 TrustManager + 随机数生成器
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new SecureRandom());

            // 3. 创建并配置 SSLParameters 以禁用主机名验证
            //    默认值是 "HTTPS"，会校验证书域名与访问的 host 是否一致
            //    设为 null 表示关闭该校验（本地 IP 访问时证书域名必然不匹配）
            SSLParameters sslParameters = new SSLParameters();
            sslParameters.setEndpointIdentificationAlgorithm(null);

            // 4. 构建自定义 HttpClient：注入 SSL 上下文 + 30 秒连接超时
            HttpClient.Builder httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).sslContext(sslContext).sslParameters(sslParameters);

            // 5. 构建请求模板：所有请求都带上 Authorization 头（Bearer Token 认证）
            //    MCP Server 可以校验此 Token，实现接口鉴权
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder().uri(URI.create(baseUrl)).header("Authorization", "Bearer abc123456789");

            // 6. 构建 SSE 传输层：把自定义 HttpClient 和请求模板注入
            HttpClientSseClientTransport transport = HttpClientSseClientTransport.builder(baseUrl).sseEndpoint(endpoint).clientBuilder(httpClient).requestBuilder(requestBuilder).build();

            // 7. 创建 MCP 同步客户端并握手
            McpSyncClient mcp = McpClient.sync(transport).build();
            mcp.initialize();
        } catch (Exception e) {
            throw new RuntimeException("创建 Insecure MCP Client 失败", e);
        }
    }


    /**
     * 创建"标准证书校验"的 HTTPS MCP 客户端（生产推荐做法）
     * <p>
     * 原理：
     * 1. 把 MCP Server 的证书（或签发它的 CA 证书）导入本地 KeyStore
     * 2. 用 KeyStore 初始化 TrustManagerFactory → 生成标准 TrustManager
     * 3. SSL 握手时，服务端出示证书 → 客户端在信任库中查找 → 找到则通过
     * <p>
     * 与 createInsecureHttpsClient 的区别：
     * - 不跳过任何校验，只把"指定的证书"加入信任（而不是信任所有）
     * - 保留了主机名验证（证书域名需与 host 一致，或使用 IP SAN）
     * <p>
     * 证书从哪来？
     * - 服务端用 openssl/JDK keytool 生成自签名证书时，同时导出 .crt 文件
     * - 或从正规 CA（如 Let's Encrypt）签发的证书链
     * - 客户端拿到 .crt 文件路径，导入信任库
     *
     * @param baseUrl    MCP Server 地址（如 https://127.0.0.1:8443）
     * @param endpoint   SSE 端点路径（如 /sse）
     * @param caCertPath 服务端证书/CA 证书文件路径（如 mcp-server.crt）
     */
    public static void createSecureHttpsClientPoc(String baseUrl, String endpoint, String caCertPath) {
        try {
            // 1. 加载 CA 证书
            //    CertificateFactory：X.509 标准证书解析器
            //    从 .crt 文件读取证书对象
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            FileInputStream fis = new FileInputStream(caCertPath);
            Certificate caCert = cf.generateCertificate(fis);
            fis.close();

            // 2. 创建 KeyStore 并导入 CA
            //    KeyStore = 证书仓库（内存版，不落盘）
            //    setCertificateEntry：以别名 "caCert" 存入证书
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);  // null,null = 创建空仓库（非文件型）
            ks.setCertificateEntry("caCert", caCert);

            // 3. 构建 TrustManagerFactory
            //    从 KeyStore 生成标准的 TrustManager（带真实校验逻辑）
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);

            // 4. 创建 SSLContext：使用上面的 TrustManager（只信任导入的证书）
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, tmf.getTrustManagers(), new SecureRandom());

            // 5. 使用默认 Hostname 验证
            //    不设置 SSLParameters → 保留默认的域名校验（更安全）
            //    注意：自签名证书需要包含对应的 IP/域名 SAN，否则会校验失败
            HttpClient.Builder httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).sslContext(sslContext);


            // 6. 构建 SSE Transport
            //    请求模板带 Authorization 头（Bearer Token 认证） -----> 实现 mcp 服务认证鉴权关键！！
            HttpClientSseClientTransport transport = HttpClientSseClientTransport
                    .builder(baseUrl)
                    .sseEndpoint(endpoint)
                    .clientBuilder(httpClient)
                    // 构造请求模板：所有请求都带上 Authorization 头（Bearer Token 认证）
                    .requestBuilder(HttpRequest.newBuilder().header("Authorization", "Bearer abc123456789"))
                    .build();

            // 7. 初始化 MCP Client（握手：交换能力 + 协商协议版本）
            McpSyncClient mcp = McpClient.sync(transport).build();
            mcp.initialize();
            System.out.println("MCP Client 初始化成功");

        } catch (Exception e) {
            throw new RuntimeException("创建 Secure MCP Client 失败", e);
        }
    }

    /**
     * 测试入口
     * <p>
     * 前置条件：
     * 1. 本地 8443 端口运行着 HTTPS MCP Server（mcp-server-sse-https 模块）
     * 2. 服务端使用自签名证书，且已导出 .crt 文件
     * <p>
     * 两种测试方式：
     * - createInsecureHttpsClient：跳过证书校验，直接连（快速验证连通性）
     * - createSecureHttpsClientPoc：导入证书后标准校验（验证证书配置正确性）
     */
    public static void main(String[] args) {

        // createInsecureHttpsClient("https://127.0.0.1:8443", "/sse");
        createSecureHttpsClientPoc(
                "https://127.0.0.1:8443",
                "/sse",
                "D:\\LLMentor\\LLMentor\\mcp\\mcp-server-sse-https\\src\\main\\resources\\mcp-server.crt"
        );
    }
}
