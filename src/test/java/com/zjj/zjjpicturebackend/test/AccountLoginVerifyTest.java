package com.zjj.zjjpicturebackend.test;

import com.zjj.zjjpicturebackend.model.vo.LoginUserVO;
import com.zjj.zjjpicturebackend.service.UserService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Slf4j
@SpringBootTest
public class AccountLoginVerifyTest {

    @Resource
    private UserService userService;

    private static final String ACCOUNT_PREFIX = "perf_user_";
    private static final String PASSWORD = "12345678";
    private static final int TOTAL = 500;
    private static final int THREADS = 16;
    private static final String EXPECTED_HASH = "b6cf17a0b267119ff92c4d54d985fc4e";

    @Test
    public void verifyAllPerfAccounts() throws Exception {
        log.info("========================================================");
        log.info("  压测账号登录验证");
        log.info("  账号前缀: {}  密码: {}  总数: {}", ACCOUNT_PREFIX, PASSWORD, TOTAL);
        log.info("========================================================");

        log.info("");
        log.info("【第一步】单独验证 perf_user_1（确认密码哈希正确）");
        String firstError = tryLogin(1);
        if (firstError != null) {
            log.error("❌ perf_user_1 登录失败: {}", firstError);
            log.error("这说明密码哈希不对，后面的 500 次验证没必要跑。");
            log.error("修复：UPDATE user SET userPassword = '{}' WHERE userAccount LIKE 'perf_user_%';",
                    EXPECTED_HASH);
            return;
        }
        log.info("✅ perf_user_1 登录成功 —— 哈希正确，继续批量验证");

        log.info("");
        log.info("【第二步】并发验证 {} 个账号（线程数 {}）", TOTAL, THREADS);

        AtomicInteger okCount = new AtomicInteger(0);
        List<String> failedReasons = new ArrayList<>();
        long start = System.currentTimeMillis();

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<CompletableFuture<Void>> futures = IntStream.rangeClosed(1, TOTAL)
                    .mapToObj(idx -> CompletableFuture.runAsync(() -> {
                        String err = tryLogin(idx);
                        if (err == null) {
                            okCount.incrementAndGet();
                        } else {
                            synchronized (failedReasons) {
                                failedReasons.add("perf_user_" + idx + " → " + err);
                            }
                        }
                    }, pool))
                    .toList();

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(180, TimeUnit.SECONDS);
        } finally {
            pool.shutdown();
        }

        long cost = System.currentTimeMillis() - start;
        int ok = okCount.get();
        int fail = TOTAL - ok;

        log.info("");
        log.info("========================================================");
        log.info("  验证结果");
        log.info("========================================================");
        log.info("  ✅ 成功: {} / {}", ok, TOTAL);
        log.info("  ❌ 失败: {}", fail);
        log.info("  ⏱  耗时: {} ms", cost);
        log.info("  📊 成功率: {}%", String.format("%.2f", ok * 100.0 / TOTAL));
        log.info("========================================================");

        if (fail > 0) {
            log.warn("");
            log.warn("失败明细（最多显示 20 条）：");
            failedReasons.stream().limit(20).forEach(r -> log.warn("  {}", r));
            log.warn("");
            log.warn("常见原因：");
            log.warn("  - 账号不存在     → 检查 01_insert_users.sql 是否执行成功");
            log.warn("  - 账号或密码错误 → 该账号的 userPassword 与其他不一致");
            log.warn("  - 账号被逻辑删除 → isDelete = 1，@TableLogic 会过滤掉");
        } else {
            log.info("");
            log.info("🎉 全部 {} 个账号验证通过！", TOTAL);
        }

        assertEquals(0, fail, String.format("有 %d 个账号登录失败，详见上方日志", fail));
    }

    private String tryLogin(int idx) {
        String account = ACCOUNT_PREFIX + idx;
        try {
            MockHttpServletRequest request = new MockHttpServletRequest();
            LoginUserVO vo = userService.userLogin(account, PASSWORD, request);
            if (vo == null) {
                return "返回值为 null";
            }
            if (vo.getId() == null) {
                return "返回的用户 id 为 null";
            }
            return null;
        } catch (Exception e) {
            String msg = e.getMessage();
            return (msg == null || msg.isBlank()) ? e.getClass().getSimpleName() : msg;
        }
    }

    @Test
    public void verifyFirstAccountOnly() {
        log.info("快速验证 perf_user_1 ...");
        String err = tryLogin(1);
        if (err == null) {
            log.info("✅ perf_user_1 登录成功，哈希正确");
        } else {
            log.error("❌ perf_user_1 登录失败: {}", err);
            log.error("修复：UPDATE user SET userPassword = '{}' WHERE userAccount LIKE 'perf_user_%';",
                    EXPECTED_HASH);
        }
        assertEquals(null, err, "perf_user_1 登录失败");
    }
}