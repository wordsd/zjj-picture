package com.zjj.zjjpicturebackend.manager.sharding;

import com.baomidou.mybatisplus.extension.toolkit.SqlRunner;
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
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.SQLException;
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
 */
@Component
@Slf4j
public class DynamicShardingManager {

    @Resource
    private DataSource dataSource;

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

    @PostConstruct
    public void initialize() {
        if (!dynamicShardingEnabled) {
            log.warn("动态分表已关闭（sharding.dynamic.enabled=false），picture 表不分表");
            return;
        }
        log.info("初始化动态分表配置...");
        updateShardingTableNodes();
    }

    /**
     * 获取所有动态表名，包括初始表 picture 和分表 picture_{spaceId}
     */
    private Set<String> fetchAllPictureTableNames() {
        // 为了测试方便，直接对所有团队空间分表（实际上线改为仅对旗舰版生效）
        Set<Long> spaceIds = spaceService.lambdaQuery()
                .eq(Space::getSpaceType, SpaceTypeEnum.TEAM.getValue())
                .list()
                .stream()
                .map(Space::getId)
                .collect(Collectors.toSet());
        Set<String> tableNames = spaceIds.stream()
                .map(spaceId -> LOGIC_TABLE_NAME + "_" + spaceId)
                .collect(Collectors.toSet());
        tableNames.add(LOGIC_TABLE_NAME); // 添加初始逻辑表
        return tableNames;
    }

    /**
     * 更新 ShardingSphere 的 actual-data-nodes 动态表名配置
     */
    private void updateShardingTableNodes() {
        Set<String> tableNames = fetchAllPictureTableNames();
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
            Long spaceId = space.getId();
            String tableName = "picture_" + spaceId;
            // 创建新表
            String createTableSql = "CREATE TABLE " + tableName + " LIKE picture";
            try {
                SqlRunner.db().update(createTableSql);
                // 更新分片规则，让新分表参与路由
                if (dynamicShardingEnabled) {
                    updateShardingTableNodes();
                }
            } catch (Exception e) {
                log.error("创建图片空间分表失败，空间 id = {}", space.getId(), e);
            }
        }
    }

}
