package com.zjj.zjjpicturebackend.manager.sharding;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.toolkit.SqlRunner;
import com.zjj.zjjpicturebackend.config.RawDataSourceProperties;
import com.zjj.zjjpicturebackend.model.dto.space.Space;
import com.zjj.zjjpicturebackend.model.enums.SpaceLevelEnum;
import com.zjj.zjjpicturebackend.model.enums.SpaceTypeEnum;
import com.zjj.zjjpicturebackend.service.SpaceService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.shardingsphere.driver.jdbc.core.connection.ShardingSphereConnection;
import org.apache.shardingsphere.infra.metadata.database.ShardingSphereDatabase;
import org.apache.shardingsphere.infra.metadata.database.rule.RuleMetaData;
import org.apache.shardingsphere.mode.manager.ContextManager;
import org.apache.shardingsphere.sharding.api.config.ShardingRuleConfiguration;
import org.apache.shardingsphere.sharding.api.config.rule.ShardingTableRuleConfiguration;
import org.apache.shardingsphere.sharding.rule.ShardingRule;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 动态分表管理器（ShardingSphere 5.5.3）
 * <p>
 * 相对 5.2.0 版本的 API 调整：
 * <ul>
 *     <li>{@code ShardingSphereRuleMetaData} 已改名为 {@code RuleMetaData}</li>
 *     <li>{@code ShardingSphereMetaData#getDatabases()} 已删除，改用 {@code ContextManager#getDatabase(String)}</li>
 *     <li>{@code ContextManager#alterRuleConfiguration} 已删除，
 *         改为 {@code getMetaDataContextManager().getDatabaseRuleConfigurationManager().refresh(...)}</li>
 * </ul>
 * <p>
 * 分表规则：仅「旗舰版团队空间」拥有独立分表 picture_{spaceId}，
 * 其余空间（私有空间、普通/专业版团队空间）的图片都留在主表 picture 里。
 * PictureShardingAlgorithm 只会路由到 actual-data-nodes 里存在的表名，
 * 所以「路由目标集合」必须与「物理分表」严格一致，否则会路由到不存在的表并报
 * Table 'xxx.picture_{spaceId}' doesn't exist。
 * <p>
 * 注意：建分表与搬迁历史数据<b>必须绕开 ShardingSphere</b>，直接用底层 MySQL 连接执行。
 * 因为新增的分表在 ShardingSphere 元数据里还不存在，走 ShardingSphere 会在 binder 阶段
 * 直接抛 {@code TableNotFoundException}（表还没建就查不到，建表语句本身也过不去）。
 */
@Component
@Slf4j
@EnableConfigurationProperties(RawDataSourceProperties.class)
public class DynamicShardingManager {

    /**
     * ShardingSphere 数据源（负责逻辑表路由）
     * <p>
     * 按类型注入即可：容器里只有这一个 DataSource Bean
     * （原始连接不再以 Bean 形式存在，见 {@link RawDataSourceProperties}）
     */
    @Resource
    private DataSource dataSource;

    /**
     * 原生 MySQL 连接配置，用于绕开 ShardingSphere 建分表 / 迁移数据
     */
    @Resource
    private RawDataSourceProperties rawDataSourceProperties;

    @Resource
    private SpaceService spaceService;

    /**
     * 是否启用动态分表
     * <p>
     * 关闭时 picture 表不分表：既不查库、也不改写分片规则，便于灰度开启
     */
    @Value("${sharding.dynamic.enabled:false}")
    private boolean dynamicShardingEnabled;

    private static final String LOGIC_TABLE_NAME = "picture";

    /**
     * 分表名格式：picture_{spaceId}
     */
    private static final String SHARD_TABLE_PATTERN = "picture_(\\d+)";

    @PostConstruct
    public void initialize() {
        if (!dynamicShardingEnabled) {
            log.warn("动态分表已关闭（sharding.dynamic.enabled=false），picture 表不分表");
            return;
        }
        if (StrUtil.hasBlank(rawDataSourceProperties.getJdbcUrl(),
                rawDataSourceProperties.getUsername(),
                rawDataSourceProperties.getPassword())) {
            log.error("未配置 sharding.raw-datasource（jdbc-url / username / password），"
                    + "跳过动态分表对账，以免误判分表缺失");
            return;
        }
        log.info("初始化动态分表配置...");
        // 顺序不能变：
        // ① 先用原生连接把物理分表补齐（此时还不能走 ShardingSphere）
        // ② 再用真实存在的分表刷新路由
        // ③ 最后才迁移主表里的历史数据（迁移语句依赖刷新后的路由）
        Set<String> tableNames = ensureShardTables();
        refreshShardingTableNodes(tableNames);
        migrateLegacyPictures(fetchShardingSpaceIds());
    }

    /**
     * 需要拥有独立分表的团队空间 id（分表名 = picture_{spaceId}）
     * <p>
     * 注意：这里必须与 {@link #createSpacePictureTable} 的建表条件保持一致
     */
    private Set<Long> fetchShardingSpaceIds() {
        return spaceService.lambdaQuery()
                .eq(Space::getSpaceType, SpaceTypeEnum.TEAM.getValue())
                .eq(Space::getSpaceLevel, SpaceLevelEnum.FLAGSHIP.getValue())
                .list()
                .stream()
                .map(Space::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * 确保每个旗舰版团队空间都有物理分表，返回应当参与路由的表名
     *
     * @return 参与路由的表名集合（含逻辑表 picture）
     */
    private Set<String> ensureShardTables() {
        Set<Long> spaceIds = fetchShardingSpaceIds();
        Set<String> tableNames = new LinkedHashSet<>();
        // 主表始终参与路由，承载公共图库与非旗舰版空间的图片
        tableNames.add(LOGIC_TABLE_NAME);
        for (Long spaceId : spaceIds) {
            String tableName = LOGIC_TABLE_NAME + "_" + spaceId;
            if (executeDdlOnShard("CREATE TABLE IF NOT EXISTS " + tableName + " LIKE " + LOGIC_TABLE_NAME)) {
                tableNames.add(tableName);
            } else {
                // 建表失败就不放进路由，避免再出现「路由里有、库里没有」的幽灵分表
                log.error("创建图片空间分表 {} 失败，本次不参与分表路由", tableName);
            }
        }
        logOrphanShardTables(spaceIds);
        log.info("动态分表对账完成，参与路由的表: {}", tableNames);
        return tableNames;
    }

    /**
     * 把主表里历史遗留的、本该在分表里的图片搬进分表
     * <p>
     * 必须走原生连接：语句里显式出现分表名，而新建的分表不在 ShardingSphere 元数据里，
     * 走 ShardingSphere 会在 binder 的 checkTableExists 阶段直接抛 TableNotFoundException
     *
     * @param spaceIds 拥有独立分表的团队空间 id
     */
    private void migrateLegacyPictures(Set<Long> spaceIds) {
        for (Long spaceId : spaceIds) {
            String tableName = LOGIC_TABLE_NAME + "_" + spaceId;
            String countSql = "SELECT COUNT(*) FROM " + LOGIC_TABLE_NAME + " WHERE spaceId = ?";
            String insertSql = "INSERT INTO " + tableName + " SELECT * FROM " + LOGIC_TABLE_NAME + " WHERE spaceId = ?";
            String deleteSql = "DELETE FROM " + LOGIC_TABLE_NAME + " WHERE spaceId = ?";
            try (Connection connection = openRawConnection()) {
                // 复制和删除放在一个事务里：要么都成功，要么都回滚，不会出现数据丢失或重复
                connection.setAutoCommit(false);
                try (PreparedStatement countStatement = connection.prepareStatement(countSql);
                     PreparedStatement insertStatement = connection.prepareStatement(insertSql);
                     PreparedStatement deleteStatement = connection.prepareStatement(deleteSql)) {
                    countStatement.setLong(1, spaceId);
                    long pending = queryCount(countStatement);
                    if (pending <= 0) {
                        // 主表里没有该空间的遗留数据，属于正常情况（迁移早已完成）
                        connection.rollback();
                        log.info("空间 {} 无需迁移，主表 {} 中已是 0 条", spaceId, LOGIC_TABLE_NAME);
                        continue;
                    }
                    insertStatement.setLong(1, spaceId);
                    int migrated = insertStatement.executeUpdate();
                    if (migrated != pending) {
                        // 行数对不上就不敢删主表，回滚保留数据，下次启动重试
                        connection.rollback();
                        log.error("空间 {} 迁移行数不一致（主表 {} 条，实插 {} 条），已回滚，数据仍保留在主表",
                                spaceId, pending, migrated);
                        continue;
                    }
                    deleteStatement.setLong(1, spaceId);
                    deleteStatement.executeUpdate();
                    connection.commit();
                    log.info("已把主表中空间 {} 的 {} 条图片迁移到分表 {}", spaceId, migrated, tableName);
                } catch (Exception e) {
                    connection.rollback();
                    throw e;
                }
            } catch (Exception e) {
                log.error("迁移主表中空间 {} 的图片到分表失败，本次跳过，数据仍保留在主表", spaceId, e);
            }
        }
    }

    /**
     * 执行 COUNT 查询并取回首行数值
     *
     * @param countStatement 已绑定参数的 COUNT 语句
     * @return 计数值，取不到时返回 0
     */
    private long queryCount(PreparedStatement countStatement) throws SQLException {
        try (ResultSet resultSet = countStatement.executeQuery()) {
            if (resultSet.next()) {
                return resultSet.getLong(1);
            }
        }
        return 0L;
    }

    /**
     * 获取底层 MySQL 原生连接（绕过 ShardingSphere 代理）
     * <p>
     * 涉及新建分表的 DDL / 数据搬迁必须用它：新分表不在 ShardingSphere 元数据里，
     * 走 ShardingSphere 会在 binder 阶段抛 TableNotFoundException。
     * <p>
     * 这里用 DriverManager 直连，而不是再定义一个 DataSource Bean：
     * 容器里多一个 DataSource 会让 {@code @Resource} 按类型注入选错对象
     * （曾导致 ShardingSphere 数据源被换成了原生连接）
     *
     * @return 原生连接，调用方负责关闭
     */
    private Connection openRawConnection() throws SQLException {
        return DriverManager.getConnection(rawDataSourceProperties.getJdbcUrl(),
                rawDataSourceProperties.getUsername(),
                rawDataSourceProperties.getPassword());
    }

    /**
     * 用底层 MySQL 连接执行建表语句
     *
     * @param sql 建表语句
     * @return 是否执行成功
     */
    private boolean executeDdlOnShard(String sql) {
        try (Connection rawConnection = openRawConnection();
             Statement statement = rawConnection.createStatement()) {
            statement.execute(sql);
            return true;
        } catch (Exception e) {
            log.error("执行分表 DDL 失败，sql = {}", sql, e);
            return false;
        }
    }

    /**
     * 只记录不再对应任何旗舰版团队空间的孤儿分表，不自动删除，避免误删数据
     */
    private void logOrphanShardTables(Set<Long> validSpaceIds) {
        try {
            List<String> orphanTables = SqlRunner.db()
                    .selectObjs("SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()")
                    .stream()
                    .map(Object::toString)
                    .filter(name -> name.matches(SHARD_TABLE_PATTERN))
                    .filter(name -> !validSpaceIds.contains(parseShardTableSpaceId(name)))
                    .collect(Collectors.toList());
            if (!orphanTables.isEmpty()) {
                log.warn("检测到不再对应旗舰版团队空间的孤儿分表（不会自动删除，请确认后可手工清理）: {}", orphanTables);
            }
        } catch (Exception e) {
            log.warn("检查孤儿分表失败，已跳过", e);
        }
    }

    /**
     * 从分表名中解析空间 id
     */
    private Long parseShardTableSpaceId(String tableName) {
        return Long.valueOf(tableName.substring((LOGIC_TABLE_NAME + "_").length()));
    }

    /**
     * 更新 ShardingSphere 的 actual-data-nodes 动态表名配置
     *
     * @param tableNames 参与路由的表名（含逻辑表 picture）
     */
    private void refreshShardingTableNodes(Set<String> tableNames) {
        String newActualDataNodes = tableNames.stream()
                .map(tableName -> "zjj_picture." + tableName) // 确保前缀合法
                .collect(Collectors.joining(","));
        log.info("动态分表 actual-data-nodes 配置: {}", newActualDataNodes);

        try (ShardingSphereConnection connection = dataSource.getConnection().unwrap(ShardingSphereConnection.class)) {
            ContextManager contextManager = connection.getContextManager();
            // 5.5.3 起可用 connection 直接取当前逻辑库名（YAML 模式下默认是 logic_db）
            String databaseName = connection.getCurrentDatabaseName();
            ShardingSphereDatabase database = contextManager.getDatabase(databaseName);
            RuleMetaData ruleMetaData = database.getRuleMetaData();

            Optional<ShardingRule> shardingRule = ruleMetaData.findSingleRule(ShardingRule.class);
            if (shardingRule.isEmpty()) {
                log.error("未找到 ShardingSphere 的分片规则配置，动态分表更新失败。");
                return;
            }
            ShardingRuleConfiguration ruleConfig = shardingRule.get().getConfiguration();
            List<ShardingTableRuleConfiguration> updatedRules = ruleConfig.getTables()
                    .stream()
                    .map(oldTableRule -> {
                        if (LOGIC_TABLE_NAME.equals(oldTableRule.getLogicTable())) {
                            ShardingTableRuleConfiguration newTableRuleConfig = new ShardingTableRuleConfiguration(LOGIC_TABLE_NAME, newActualDataNodes);
                            newTableRuleConfig.setDatabaseShardingStrategy(oldTableRule.getDatabaseShardingStrategy());
                            newTableRuleConfig.setTableShardingStrategy(oldTableRule.getTableShardingStrategy());
                            newTableRuleConfig.setKeyGenerateStrategy(oldTableRule.getKeyGenerateStrategy());
                            newTableRuleConfig.setAuditStrategy(oldTableRule.getAuditStrategy());
                            return newTableRuleConfig;
                        }
                        return oldTableRule;
                    })
                    .collect(Collectors.toList());
            ruleConfig.setTables(updatedRules);
            // 5.5.3 里 alterRuleConfiguration 已被 DatabaseRuleConfigurationManager#refresh 取代
            contextManager.getMetaDataContextManager()
                    .getDatabaseRuleConfigurationManager()
                    .refresh(databaseName, ruleConfig, true);
            log.info("动态分表规则更新成功！");
        } catch (SQLException e) {
            throw new RuntimeException("更新 ShardingSphere 分片规则失败", e);
        }
    }

    /**
     * 为团队旗舰版空间创建分表
     *
     * @param space 空间
     */
    public void createSpacePictureTable(Space space) {
        // 仅为旗舰版团队空间创建分表
        if (space.getSpaceType() == SpaceTypeEnum.TEAM.getValue() && space.getSpaceLevel() == SpaceLevelEnum.FLAGSHIP.getValue()) {
            if (!dynamicShardingEnabled) {
                log.warn("动态分表已关闭，跳过为空间 {} 创建分表", space.getId());
                return;
            }
            Long spaceId = space.getId();
            String tableName = LOGIC_TABLE_NAME + "_" + spaceId;
            // 必须走原生连接：新分表此时还不在 ShardingSphere 元数据里
            if (!executeDdlOnShard("CREATE TABLE IF NOT EXISTS " + tableName + " LIKE " + LOGIC_TABLE_NAME)) {
                // 建表失败就不刷新路由，避免把不存在的表写进 actual-data-nodes
                log.error("为空间 {} 创建分表失败，本次不参与分表路由", spaceId);
                return;
            }
            // 更新分片规则，让新分表参与路由
            refreshShardingTableNodes(collectPictureTableNames());
        }
    }

    /**
     * 获取参与路由的所有表名：逻辑表 picture + 所有旗舰版团队空间的分表
     */
    private Set<String> collectPictureTableNames() {
        Set<String> tableNames = fetchShardingSpaceIds().stream()
                .map(spaceId -> LOGIC_TABLE_NAME + "_" + spaceId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        tableNames.add(LOGIC_TABLE_NAME); // 添加初始逻辑表
        return tableNames;
    }

}
