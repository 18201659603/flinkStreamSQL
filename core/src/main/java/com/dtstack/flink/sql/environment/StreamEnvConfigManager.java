/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.dtstack.flink.sql.environment;

import com.dtstack.flink.sql.constrant.ConfigConstrant;
import com.dtstack.flink.sql.enums.EStateBackend;
import com.dtstack.flink.sql.util.MathUtil;
import com.dtstack.flink.sql.util.PropertiesUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.flink.api.common.ExecutionConfig;
import org.apache.flink.api.common.restartstrategy.RestartStrategies;
import org.apache.flink.api.common.time.Time;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.contrib.streaming.state.RocksDBStateBackend;
import org.apache.flink.runtime.state.StateBackend;
import org.apache.flink.runtime.state.filesystem.FsStateBackend;
import org.apache.flink.runtime.state.memory.MemoryStateBackend;
import org.apache.flink.streaming.api.CheckpointingMode;
import org.apache.flink.streaming.api.TimeCharacteristic;
import org.apache.flink.streaming.api.environment.CheckpointConfig;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.api.TableConfig;
import org.apache.flink.table.api.TableEnvironment;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 *
 *  流执行环境相关配置
 * Date: 2019/11/22
 * Company: www.dtstack.com
 * @author maqi
 */
public final class StreamEnvConfigManager {
    private StreamEnvConfigManager() {
        throw new AssertionError("Singleton class.");
    }

    /**
     * 配置StreamExecutionEnvironment运行时参数
     * @param streamEnv
     * @param confProperties
     */
    public static void streamExecutionEnvironmentConfig(StreamExecutionEnvironment streamEnv, Properties confProperties)
            throws NoSuchMethodException, InvocationTargetException, IllegalAccessException, IOException {

        confProperties = PropertiesUtils.propertiesTrim(confProperties);
        streamEnv.getConfig().disableClosureCleaner();

        Configuration globalJobParameters = new Configuration();
        //Configuration unsupported set properties key-value
        Method method = Configuration.class.getDeclaredMethod("setValueInternal", String.class, Object.class);
        method.setAccessible(true);
        for (Map.Entry<Object, Object> prop : confProperties.entrySet()) {
            method.invoke(globalJobParameters, prop.getKey(), prop.getValue());
        }

        ExecutionConfig exeConfig = streamEnv.getConfig();
        if (exeConfig.getGlobalJobParameters() == null) {
            exeConfig.setGlobalJobParameters(globalJobParameters);
        } else if (exeConfig.getGlobalJobParameters() instanceof Configuration) {
            ((Configuration) exeConfig.getGlobalJobParameters()).addAll(globalJobParameters);
        }

        Optional<Integer> envParallelism = getEnvParallelism(confProperties);
        if (envParallelism.isPresent()) {
            streamEnv.setParallelism(envParallelism.get());
        }

        Optional<Integer> maxParallelism = getMaxEnvParallelism(confProperties);
        if (maxParallelism.isPresent()) {
            streamEnv.setMaxParallelism(maxParallelism.get());
        }

        Optional<Long> bufferTimeoutMillis = getBufferTimeoutMillis(confProperties);
        if (bufferTimeoutMillis.isPresent()) {
            streamEnv.setBufferTimeout(bufferTimeoutMillis.get());
        }

        Optional<TimeCharacteristic> streamTimeCharacteristic = getStreamTimeCharacteristic(confProperties);
        if (streamTimeCharacteristic.isPresent()) {
            streamEnv.setStreamTimeCharacteristic(streamTimeCharacteristic.get());
        }

        streamEnv.setRestartStrategy(RestartStrategies.failureRateRestart(
                ConfigConstrant.failureRate,
                Time.of(ConfigConstrant.failureInterval, TimeUnit.MINUTES),
                Time.of(ConfigConstrant.delayInterval, TimeUnit.SECONDS)
        ));

        // checkpoint config
        Optional<Boolean> checkpointingEnabled = isCheckpointingEnabled(confProperties);
        if (checkpointingEnabled.get()) {
            Optional<Long> checkpointInterval = getCheckpointInterval(confProperties);
            streamEnv.enableCheckpointing(checkpointInterval.get());

            Optional<CheckpointingMode> checkpointingMode = getCheckpointingMode(confProperties);
            if (checkpointingMode.isPresent()) {
                streamEnv.getCheckpointConfig().setCheckpointingMode(checkpointingMode.get());
            }
            Optional<Long> checkpointTimeout = getCheckpointTimeout(confProperties);
            if (checkpointTimeout.isPresent()) {
                streamEnv.getCheckpointConfig().setCheckpointTimeout(checkpointTimeout.get());
            }

            Optional<Integer> maxConcurrentCheckpoints = getMaxConcurrentCheckpoints(confProperties);
            if (maxConcurrentCheckpoints.isPresent()) {
                streamEnv.getCheckpointConfig().setMaxConcurrentCheckpoints(maxConcurrentCheckpoints.get());
            }

            Optional<CheckpointConfig.ExternalizedCheckpointCleanup> checkpointCleanup = getCheckpointCleanup(confProperties);
            if (checkpointCleanup.isPresent()) {
                streamEnv.getCheckpointConfig().enableExternalizedCheckpoints(checkpointCleanup.get());
            }

            Optional<StateBackend> stateBackend = getStateBackend(confProperties);
            if (stateBackend.isPresent()) {
                streamEnv.setStateBackend(stateBackend.get());
            }
        }
    }

    /**
     * 设置TableEnvironment状态超时时间
     * @param tableEnv
     * @param confProperties
     */
    public static void streamTableEnvironmentStateTTLConfig(TableEnvironment tableEnv, Properties confProperties) {
        confProperties = PropertiesUtils.propertiesTrim(confProperties);
        Optional<Tuple2<Time, Time>> tableEnvTTL = getTableEnvTTL(confProperties);
        if (tableEnvTTL.isPresent()) {
            Tuple2<Time, Time> timeRange = tableEnvTTL.get();
            TableConfig qConfig = tableEnv.getConfig();
            qConfig.setIdleStateRetentionTime(timeRange.f0, timeRange.f1);
        }
    }


    // -----------------------StreamExecutionEnvironment config-----------------------------------------------
    public static Optional<Integer> getEnvParallelism(Properties properties) {
        String parallelismStr = properties.getProperty(ConfigConstrant.SQL_ENV_PARALLELISM);
        return StringUtils.isNotBlank(parallelismStr) ? Optional.of(Integer.valueOf(parallelismStr)) : Optional.empty();
    }

    public static Optional<Integer> getMaxEnvParallelism(Properties properties) {
        String parallelismStr = properties.getProperty(ConfigConstrant.SQL_MAX_ENV_PARALLELISM);
        return StringUtils.isNotBlank(parallelismStr) ? Optional.of(Integer.valueOf(parallelismStr)) : Optional.empty();
    }

    public static Optional<Long> getBufferTimeoutMillis(Properties properties) {
        String mills = properties.getProperty(ConfigConstrant.SQL_BUFFER_TIMEOUT_MILLIS);
        return StringUtils.isNotBlank(mills) ? Optional.of(Long.valueOf(mills)) : Optional.empty();
    }

    /**
     * #ProcessingTime(默认), IngestionTime, EventTime
     * @param properties
     */
    public static Optional<TimeCharacteristic> getStreamTimeCharacteristic(Properties properties) {
        if (!properties.containsKey(ConfigConstrant.FLINK_TIME_CHARACTERISTIC_KEY)) {
            return Optional.empty();
        }
        String characteristicStr = properties.getProperty(ConfigConstrant.FLINK_TIME_CHARACTERISTIC_KEY);
        Optional<TimeCharacteristic> characteristic = Arrays.stream(TimeCharacteristic.values())
                .filter(tc -> !characteristicStr.equalsIgnoreCase(tc.toString())).findAny();

        if (!characteristic.isPresent()) {
            throw new RuntimeException("illegal property :" + ConfigConstrant.FLINK_TIME_CHARACTERISTIC_KEY);
        }
        return characteristic;
    }

    public static Optional<Boolean> isCheckpointingEnabled(Properties properties) {
        boolean checkpointEnabled = properties.getProperty(ConfigConstrant.SQL_CHECKPOINT_INTERVAL_KEY) == null
                && properties.getProperty(ConfigConstrant.FLINK_CHECKPOINT_INTERVAL_KEY) == null;
        return Optional.of(checkpointEnabled);
    }

    public static Optional<Long> getCheckpointInterval(Properties properties) {
        // 两个参数主要用来做上层兼容
        Long sql_interval = Long.valueOf(properties.getProperty(ConfigConstrant.SQL_CHECKPOINT_INTERVAL_KEY, "0"));
        Long flink_interval = Long.valueOf(properties.getProperty(ConfigConstrant.FLINK_CHECKPOINT_INTERVAL_KEY, "0"));
        long checkpointInterval = Math.max(sql_interval, flink_interval);
        return Optional.of(checkpointInterval);
    }

    public static Optional<CheckpointingMode> getCheckpointingMode(Properties properties) {
        String checkpointingModeStr = properties.getProperty(ConfigConstrant.FLINK_CHECKPOINT_MODE_KEY);
        CheckpointingMode checkpointingMode = null;
        if (!StringUtils.isEmpty(checkpointingModeStr)) {
            checkpointingMode = CheckpointingMode.valueOf(checkpointingModeStr.toUpperCase());
        }
        return checkpointingMode == null ? Optional.empty() : Optional.of(checkpointingMode);
    }

    public static Optional<Long> getCheckpointTimeout(Properties properties) {
        String checkpointTimeoutStr = properties.getProperty(ConfigConstrant.FLINK_CHECKPOINT_TIMEOUT_KEY);

        if (!StringUtils.isEmpty(checkpointTimeoutStr)) {
            Long checkpointTimeout = Long.valueOf(checkpointTimeoutStr);
            return Optional.of(checkpointTimeout);
        }
        return Optional.empty();
    }

    public static Optional<Integer> getMaxConcurrentCheckpoints(Properties properties) {
        String maxConcurrCheckpointsStr = properties.getProperty(ConfigConstrant.FLINK_MAXCONCURRENTCHECKPOINTS_KEY);
        if (!StringUtils.isEmpty(maxConcurrCheckpointsStr)) {
            Integer maxConcurrCheckpoints = Integer.valueOf(maxConcurrCheckpointsStr);
            return Optional.of(maxConcurrCheckpoints);
        }
        return Optional.empty();
    }

    public static Optional<CheckpointConfig.ExternalizedCheckpointCleanup> getCheckpointCleanup(Properties properties) {
        Boolean sqlCleanMode = MathUtil.getBoolean(properties.getProperty(ConfigConstrant.SQL_CHECKPOINT_CLEANUPMODE_KEY), false);
        Boolean flinkCleanMode = MathUtil.getBoolean(properties.getProperty(ConfigConstrant.FLINK_CHECKPOINT_CLEANUPMODE_KEY), false);

        CheckpointConfig.ExternalizedCheckpointCleanup externalizedCheckpointCleanup = (sqlCleanMode || flinkCleanMode) ?
                CheckpointConfig.ExternalizedCheckpointCleanup.DELETE_ON_CANCELLATION : CheckpointConfig.ExternalizedCheckpointCleanup.RETAIN_ON_CANCELLATION;
        return Optional.of(externalizedCheckpointCleanup);
    }

    public static Optional<StateBackend> getStateBackend(Properties properties) throws IOException {
        String backendType = properties.getProperty(ConfigConstrant.STATE_BACKEND_KEY);
        String checkpointDataUri = properties.getProperty(ConfigConstrant.CHECKPOINTS_DIRECTORY_KEY);
        String backendIncremental = properties.getProperty(ConfigConstrant.STATE_BACKEND_INCREMENTAL_KEY, "true");

        if (!StringUtils.isEmpty(backendType)) {
            return createStateBackend(backendType, checkpointDataUri, backendIncremental);
        }
        return Optional.empty();
    }

    private static Optional<StateBackend> createStateBackend(String backendType, String checkpointDataUri, String backendIncremental) throws IOException {
        EStateBackend stateBackendType = EStateBackend.convertFromString(backendType);
        StateBackend stateBackend = null;
        switch (stateBackendType) {
            case MEMORY:
                stateBackend = new MemoryStateBackend();
                break;
            case FILESYSTEM:
                checkpointDataUriEmptyCheck(checkpointDataUri, backendType);
                stateBackend = new FsStateBackend(checkpointDataUri);
                break;
            case ROCKSDB:
                checkpointDataUriEmptyCheck(checkpointDataUri, backendType);
                stateBackend = new RocksDBStateBackend(checkpointDataUri, BooleanUtils.toBoolean(backendIncremental));
                break;
        }
        return stateBackend == null ? Optional.empty() : Optional.of(stateBackend);
    }

    private static void checkpointDataUriEmptyCheck(String checkpointDataUri, String backendType) {
        if (StringUtils.isEmpty(checkpointDataUri)) {
            throw new RuntimeException(backendType + " backend checkpointDataUri not null!");
        }
    }

    // -----------------TableEnvironment state ttl config------------------------------

    private static final String TTL_PATTERN_STR = "^+?([1-9][0-9]*)([dDhHmMsS])$";
    private static final Pattern TTL_PATTERN = Pattern.compile(TTL_PATTERN_STR);

    public static Optional<Tuple2<Time, Time>> getTableEnvTTL(Properties properties) {
        String ttlMintimeStr = properties.getProperty(ConfigConstrant.SQL_TTL_MINTIME);
        String ttlMaxtimeStr = properties.getProperty(ConfigConstrant.SQL_TTL_MAXTIME);
        if (StringUtils.isNotEmpty(ttlMintimeStr) || StringUtils.isNotEmpty(ttlMaxtimeStr)) {
            verityTtl(ttlMintimeStr, ttlMaxtimeStr);
            Matcher ttlMintimeStrMatcher = TTL_PATTERN.matcher(ttlMintimeStr);
            Matcher ttlMaxtimeStrMatcher = TTL_PATTERN.matcher(ttlMaxtimeStr);

            Long ttlMintime = 0L;
            Long ttlMaxtime = 0L;
            if (ttlMintimeStrMatcher.find()) {
                ttlMintime = getTtlTime(Integer.parseInt(ttlMintimeStrMatcher.group(1)), ttlMintimeStrMatcher.group(2));
            }
            if (ttlMaxtimeStrMatcher.find()) {
                ttlMaxtime = getTtlTime(Integer.parseInt(ttlMaxtimeStrMatcher.group(1)), ttlMaxtimeStrMatcher.group(2));
            }
            if (0L != ttlMintime && 0L != ttlMaxtime) {
                return Optional.of(new Tuple2<>(Time.milliseconds(ttlMintime), Time.milliseconds(ttlMaxtime)));
            }
        }
        return Optional.empty();
    }

    /**
     * ttl 校验
     * @param ttlMintimeStr 最小时间
     * @param ttlMaxtimeStr 最大时间
     */
    private static void verityTtl(String ttlMintimeStr, String ttlMaxtimeStr) {
        if (null == ttlMintimeStr
                || null == ttlMaxtimeStr
                || !TTL_PATTERN.matcher(ttlMintimeStr).find()
                || !TTL_PATTERN.matcher(ttlMaxtimeStr).find()) {
            throw new RuntimeException("sql.ttl.min 、sql.ttl.max must be set at the same time . example sql.ttl.min=1h,sql.ttl.max=2h");
        }
    }

    /**
     * 不同单位时间到毫秒的转换
     * @param timeNumber 时间值，如：30
     * @param timeUnit 单位，d:天，h:小时，m:分，s:秒
     * @return
     */
    private static Long getTtlTime(Integer timeNumber, String timeUnit) {
        if (timeUnit.equalsIgnoreCase("d")) {
            return timeNumber * 1000l * 60 * 60 * 24;
        } else if (timeUnit.equalsIgnoreCase("h")) {
            return timeNumber * 1000l * 60 * 60;
        } else if (timeUnit.equalsIgnoreCase("m")) {
            return timeNumber * 1000l * 60;
        } else if (timeUnit.equalsIgnoreCase("s")) {
            return timeNumber * 1000l;
        } else {
            throw new RuntimeException("not support " + timeNumber + timeUnit);
        }
    }
}
