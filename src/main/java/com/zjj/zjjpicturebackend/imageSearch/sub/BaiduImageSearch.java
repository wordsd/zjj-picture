package com.zjj.zjjpicturebackend.imageSearch.sub;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 百度以图搜图 API 工具类
 * <p>
 * 移植自 PicImageSearch (Python)
 * 包含完整的 Multipart 上传、HTML 数据提取和结果解析流程
 */
@Slf4j
public class BaiduImageSearch {

    private static final String BASE_URL = "https://graph.baidu.com";
    private static final String UPLOAD_URL = BASE_URL + "/upload";

    // 使用单例 HttpClient 提高性能
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private BaiduImageSearch() {
        // 私有构造，防止实例化
    }

    /**
     * 通过图片 URL 进行搜索
     *
     * @param imageUrl 图片的公网 URL
     * @return 搜索结果
     */
    public static BaiduResponse search(String imageUrl) {
        try {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(imageUrl)).GET().build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IOException("Failed to download image from URL, status code: " + response.statusCode());
            }
            return searchInternal(response.body());
        } catch (Exception e) {
            log.error("Baidu search by URL failed: {}", imageUrl, e);
            throw new RuntimeException("Baidu Image Search failed", e);
        }
    }

    /**
     * 通过图片字节数据进行搜索 (适用于文件上传场景)
     *
     * @param imageBytes 图片二进制数据
     * @return 搜索结果
     */
    public static BaiduResponse search(byte[] imageBytes) {
        try {
            return searchInternal(imageBytes);
        } catch (Exception e) {
            log.error("Baidu search by bytes failed", e);
            throw new RuntimeException("Baidu Image Search failed", e);
        }
    }

    /**
     * 核心搜索流程
     */
    private static BaiduResponse searchInternal(byte[] imageBytes) throws IOException, InterruptedException {
        // Step 1: 上传图片获取结果页 URL
        String resultPageUrl = uploadImage(imageBytes);
        if (resultPageUrl == null) {
            log.warn("Failed to get result page URL from Baidu upload");
            return new BaiduResponse(Collections.emptyList(), Collections.emptyList(), "");
        }

        // Step 2: 获取结果页 HTML
        String htmlContent = fetchPageContent(resultPageUrl);

        // Step 3: 提取 window.cardData 数据 (使用状态机解析，更稳健)
        List<JsonNode> cardData = extractCardData(htmlContent);

        // Step 4: 解析数据并获取相似图片详情
        return parseCardData(cardData, resultPageUrl);
    }

    /**
     * 上传图片到百度
     */
    private static String uploadImage(byte[] imageBytes) throws IOException, InterruptedException {
        String boundary = "---BaiduSearchBoundary" + System.currentTimeMillis();
        List<byte[]> bodyParts = new ArrayList<>();

        // 构建 Multipart Form Data
        addFormField(bodyParts, boundary, "from", "pc");
        addFilePart(bodyParts, boundary, "image", "image.jpg", "image/jpeg", imageBytes);
        bodyParts.add(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        byte[] requestBody = joinBytes(bodyParts);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(UPLOAD_URL))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Acs-Token", "") // 必须保留，虽然为空
                .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode root = objectMapper.readTree(response.body());

        if (root.has("data") && root.get("data").has("url")) {
            return root.get("data").get("url").asText();
        }
        return null;
    }

    /**
     * 获取网页内容
     */
    private static String fetchPageContent(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString()).body();
    }

    /**
     * 从 HTML 中提取 window.cardData 的 JSON 数据
     * 使用状态机解析，支持嵌套数组和字符串转义
     */
    private static List<JsonNode> extractCardData(String html) {
        String keyword = "window.cardData";
        int index = html.indexOf(keyword);
        if (index == -1) return Collections.emptyList();

        // 寻找赋值后的第一个 '['
        int start = html.indexOf('[', index);
        if (start == -1) return Collections.emptyList();

        // 使用栈思想寻找匹配的结束 ']'
        int balance = 0;
        int end = -1;
        boolean inString = false;
        boolean escape = false;

        for (int i = start; i < html.length(); i++) {
            char c = html.charAt(i);

            if (inString) {
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }

            if (c == '"') {
                inString = true;
            } else if (c == '[') {
                balance++;
            } else if (c == ']') {
                balance--;
                if (balance == 0) {
                    end = i + 1;
                    break;
                }
            }
        }

        if (end != -1) {
            String jsonArray = html.substring(start, end);
            try {
                return objectMapper.readValue(jsonArray, new TypeReference<List<JsonNode>>() {});
            } catch (Exception e) {
                log.warn("Failed to parse extracted JSON from Baidu page", e);
            }
        }

        return Collections.emptyList();
    }

    /**
     * 解析 Card Data 并获取最终结果
     */
    private static BaiduResponse parseCardData(List<JsonNode> cardData, String resultPageUrl) throws IOException, InterruptedException {
        List<BaiduItem> exactMatches = new ArrayList<>();
        List<BaiduItem> similarMatches = new ArrayList<>();

        for (JsonNode card : cardData) {
            String cardName = card.has("cardName") ? card.get("cardName").asText() : "";

            // 无结果
            if ("noresult".equals(cardName)) {
                return new BaiduResponse(Collections.emptyList(), Collections.emptyList(), resultPageUrl);
            }

            // 来源/原图 (Same Source)
            if ("same".equals(cardName) && card.has("tplData")) {
                JsonNode sameData = card.get("tplData");
                if (sameData.has("list")) {
                    for (JsonNode item : sameData.get("list")) {
                        exactMatches.add(BaiduItem.fromJson(item));
                    }
                }
            }

            // 相似图片 (Similar Pictures) - 需要二次请求获取完整列表
            if ("simipic".equals(cardName) && card.has("tplData")) {
                JsonNode tplData = card.get("tplData");
                if (tplData.has("firstUrl")) {
                    String firstUrl = tplData.get("firstUrl").asText();
                    try {
                        String simiJson = fetchPageContent(firstUrl);
                        JsonNode simiRoot = objectMapper.readTree(simiJson);

                        if (simiRoot.has("data") && simiRoot.get("data").has("list")) {
                            for (JsonNode item : simiRoot.get("data").get("list")) {
                                similarMatches.add(BaiduItem.fromJson(item));
                            }
                        }
                    } catch (Exception e) {
                        log.warn("Failed to fetch similar images detail", e);
                    }
                }
            }
        }

        return new BaiduResponse(similarMatches, exactMatches, resultPageUrl);
    }

    // --- Multipart 辅助方法 ---

    private static void addFormField(List<byte[]> parts, String boundary, String name, String value) {
        String header = "--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n";
        parts.add(header.getBytes(StandardCharsets.UTF_8));
        parts.add(value.getBytes(StandardCharsets.UTF_8));
        parts.add("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static void addFilePart(List<byte[]> parts, String boundary, String name, String filename, String contentType, byte[] content) {
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



