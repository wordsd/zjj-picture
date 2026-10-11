package com.zjj.zjjpicturebackend.manager.sharding;

import org.apache.shardingsphere.sharding.api.sharding.standard.PreciseShardingValue;
import org.apache.shardingsphere.sharding.api.sharding.standard.RangeShardingValue;
import org.apache.shardingsphere.sharding.api.sharding.standard.StandardShardingAlgorithm;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Properties;

public class PictureShardingAlgorithm implements StandardShardingAlgorithm<Long> {

    @Override
    public String doSharding(Collection<String> availableTargetNames, PreciseShardingValue<Long> preciseShardingValue) {
        Long spaceId = preciseShardingValue.getValue();
        String logicTableName = preciseShardingValue.getLogicTableName();
        //spaceId为空表示查询所有图片
        if (spaceId == null) {
            return logicTableName;
        } else {
            //根据spaceId动态生成分表名（必须与 DynamicShardingManager 生成的 picture_{spaceId} 一致）
            String realTableName = "picture_" + spaceId;
            if (availableTargetNames.contains(realTableName)) {
                return realTableName;
            } else {
                return  logicTableName;
            }
        }


    }

    @Override
    public Collection<String> doSharding(Collection<String> collection, RangeShardingValue<Long> rangeShardingValue) {
        return new ArrayList<>();
    }

    // 注意：ShardingSphere 5.4.1 的 ShardingSphereAlgorithm 是否仍声明 getProps() 未能确认，
    // 这里故意不加 @Override —— 接口有它时算实现，没有时只是一个多余方法，两种情况都能编译
    public Properties getProps() {
        return null;
    }

    @Override
    public void init(Properties properties) {

    }
}
