package com.zjj.zjjpicturebackend.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjj.zjjpicturebackend.imageSearch.sub.BaiduImageSearch;
import com.zjj.zjjpicturebackend.imageSearch.sub.BaiduItem;
import com.zjj.zjjpicturebackend.imageSearch.sub.BaiduResponse;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 百度以图搜图链路测试
 * <p>
 * 注意：
 * 1. 该测试会真实访问 graph.baidu.com，属于外部网络依赖，百度随时可能调整接口导致失败
 * 2. 所有用例都会把原始返回打印出来，便于定位是"上传失败""页面结构变了"还是"结果为空"
 * 3. 不需要 Spring 容器，所以没有加 @SpringBootTest，跑起来更快
 */
class BaiduImageSearchTest {

    /**
     * 测试用的公网图片（换成你自己 COS 上的公网直链也可以）
     */
    private static final String TEST_IMAGE_URL = "https://mms-graph.cdn.bcebos.com/cameraGuide/right.png";

    /**
     * 本地图片路径，用于测试"读取本地文件字节再上传"这条链路
     * 文件不存在时该用例会自动跳过
     */
    private static final String LOCAL_IMAGE_PATH = "D:/code/zjj-picture-backend/src/test/resources/test-image.jpg";

    private static final String UPLOAD_URL = "https://graph.baidu.com/upload";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * 方式一：传图片 URL，由 BaiduImageSearch 自己下载后上传
     */
    @Test
    void testSearchByUrl() {
        BaiduResponse response = BaiduImageSearch.search(TEST_IMAGE_URL);
        printResult("search(String imageUrl)", response);
        assertUploadSuccess(response);
    }

    /**
     * 方式二：直接传图片字节（站内图片最终会走这条链路）
     */
    @Test
    void testSearchByBytes() throws Exception {
        byte[] imageBytes = downloadImage(TEST_IMAGE_URL);
        System.out.println("下载到的图片大小：" + imageBytes.length + " 字节");

        BaiduResponse response = BaiduImageSearch.search(imageBytes);
        printResult("search(byte[] imageBytes)", response);
        assertUploadSuccess(response);
    }

    /**
     * 方式三：读取本地图片文件再上传
     */
    @Test
    void testSearchByLocalFile() throws Exception {
        Path path = Paths.get(LOCAL_IMAGE_PATH);
        Assumptions.assumeTrue(Files.exists(path), "本地测试图片不存在，跳过该用例：" + LOCAL_IMAGE_PATH);

        byte[] imageBytes = Files.readAllBytes(path);
        System.out.println("本地图片大小：" + imageBytes.length + " 字节");

        BaiduResponse response = BaiduImageSearch.search(imageBytes);
        printResult("search(localFile)", response);
        assertUploadSuccess(response);
    }

    /**
     * 诊断用：把链路中间的原始返回打出来，用于定位卡在哪一步
     * <p>
     * 会依次打印：
     * 1. /upload 不带 uptime 的原始响应
     * 2. /upload 带 uptime 的原始响应（判断 uptime 是否是必需参数）
     * 3. 结果页 HTML 里是否含有 window.cardData / firstUrl（判断页面结构是否变了）
     * <p>
     * 该用例本身不做断言，永远"通过"，只负责输出信息
     */
    @Test
    void testDiagnoseRawChain() throws Exception {
        byte[] imageBytes = downloadImage(TEST_IMAGE_URL);

        String bodyWithoutUptime = rawUpload(imageBytes, false);
        System.out.println("========== 诊断 1：/upload 不带 uptime ==========");
        System.out.println(bodyWithoutUptime);

        Thread.sleep(2000);

        String bodyWithUptime = rawUpload(imageBytes, true);
        System.out.println("========== 诊断 2：/upload 带 uptime ==========");
        System.out.println(bodyWithUptime);

        // 从带 uptime 的返回里取结果页 URL，继续探测页面结构
        String resultPageUrl = extractDataUrl(bodyWithUptime);
        if (resultPageUrl == null) {
            resultPageUrl = extractDataUrl(bodyWithoutUptime);
        }
        if (resultPageUrl == null) {
            System.out.println("两次 upload 都没拿到 data.url，链路在第 1 步就断了");
            return;
        }

        System.out.println("========== 诊断 3：结果页结构 ==========");
        System.out.println("结果页 URL：" + resultPageUrl);
        HttpResponse<String> pageResponse = HTTP_CLIENT.send(
                HttpRequest.newBuilder().uri(URI.create(resultPageUrl)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        String html = pageResponse.body();
        System.out.println("结果页 httpStatus=" + pageResponse.statusCode() + "，HTML 长度=" + html.length());
        System.out.println("包含 window.cardData：" + html.contains("window.cardData"));
        System.out.println("包含 firstUrl       ：" + html.contains("firstUrl"));
        System.out.println("包含 noresult       ：" + html.contains("noresult"));
        System.out.println("HTML 片段：" + snippet(html, "window.cardData"));
    }

    // ---------------- 断言与打印 ----------------

    /**
     * 只要 upload 这一步通了，BaiduImageSearch 就会带出结果页 URL；
     * 取不到 URL 说明链路第一步就失败（此时去看 testDiagnoseRawChain 的输出）
     */
    private static void assertUploadSuccess(BaiduResponse response) {
        assertNotNull(response, "返回结果不应为 null");
        assertTrue(response.getUrl() != null && !response.getUrl().isEmpty(),
                "第 1 步 upload 失败：没有取到结果页 URL，请先跑 testDiagnoseRawChain 看原始返回");
    }

    private static void printResult(String scene, BaiduResponse response) {
        System.out.println("========== " + scene + " ==========");
        System.out.println("结果页 URL：" + response.getUrl());
        System.out.println("相似图片数量：" + size(response.getRaw()));
        System.out.println("来源/原图数量：" + size(response.getExactMatches()));
        printItems("相似图片", response.getRaw());
        printItems("来源/原图", response.getExactMatches());
        if (size(response.getRaw()) == 0 && size(response.getExactMatches()) == 0) {
            System.out.println("提示：upload 成功但没有解析出结果，重点看结果页里 window.cardData 的结构是否变了");
        }
    }

    private static void printItems(String label, List<BaiduItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        int limit = Math.min(items.size(), 5);
        for (int i = 0; i < limit; i++) {
            BaiduItem item = items.get(i);
            System.out.println(label + "[" + i + "] title=" + item.getTitle()
                    + " | thumbnail=" + item.getThumbnail()
                    + " | url=" + item.getUrl());
        }
    }

    private static int size(List<BaiduItem> items) {
        return items == null ? 0 : items.size();
    }

    private static String snippet(String html, String keyword) {
        int index = html.indexOf(keyword);
        if (index == -1) {
            return "（未找到关键字 " + keyword + "）";
        }
        int end = Math.min(html.length(), index + 300);
        return html.substring(index, end).replaceAll("\\s+", " ");
    }

    // ---------------- 辅助方法 ----------------

    private static byte[] downloadImage(String url) throws Exception {
        HttpResponse<byte[]> response = HTTP_CLIENT.send(
                HttpRequest.newBuilder().uri(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertTrue(response.statusCode() == 200, "测试图片下载失败，status=" + response.statusCode());
        return response.body();
    }

    /**
     * 手工打一次 /upload，返回 "httpStatus=xxx, body=xxx" 形式的原始结果
     */
    private static String rawUpload(byte[] imageBytes, boolean withUptime) throws Exception {
        String boundary = "---BaiduSearchBoundary" + System.currentTimeMillis();
        List<byte[]> parts = new ArrayList<>();
        addFormField(parts, boundary, "from", "pc");
        addFilePart(parts, boundary, "image", "image.jpg", "image/jpeg", imageBytes);
        parts.add(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        String url = UPLOAD_URL;
        if (withUptime) {
            url = url + "?uptime=" + System.currentTimeMillis();
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Acs-Token", "")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofByteArray(joinBytes(parts)))
                .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        return "httpStatus=" + response.statusCode() + ", body=" + response.body();
    }

    /**
     * 从 "httpStatus=200, body={...}" 里解析出 data.url
     */
    private static String extractDataUrl(String rawResult) {
        int index = rawResult.indexOf("body=");
        if (index == -1) {
            return null;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(rawResult.substring(index + 5));
            if (root.has("data") && root.get("data").has("url")) {
                return root.get("data").get("url").asText();
            }
        } catch (Exception e) {
            // 解析失败说明返回的不是 JSON，忽略即可
        }
        return null;
    }

    private static void addFormField(List<byte[]> parts, String boundary, String name, String value) {
        String header = "--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n";
        parts.add(header.getBytes(StandardCharsets.UTF_8));
        parts.add(value.getBytes(StandardCharsets.UTF_8));
        parts.add("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static void addFilePart(List<byte[]> parts, String boundary, String name,
                                    String filename, String contentType, byte[] content) {
        String header = "--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + filename + "\"\r\n" +
                "Content-Type: " + contentType + "\r\n\r\n";
        parts.add(header.getBytes(StandardCharsets.UTF_8));
        parts.add(content);
        parts.add("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] joinBytes(List<byte[]> parts) {
        int totalLength = parts.stream().mapToInt(b -> b.length).sum();
        byte[] result = new byte[totalLength];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, result, offset, part.length);
            offset += part.length;
        }
        return result;
    }
}
