/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.inlong.manager.service.thirdparty.sort;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.inlong.manager.common.beans.ClusterBean;
import org.apache.inlong.manager.common.enums.Constant;
import org.apache.inlong.manager.common.enums.EntityStatus;
import org.apache.inlong.manager.common.exceptions.WorkflowListenerException;
import org.apache.inlong.manager.common.pojo.group.InlongGroupExtInfo;
import org.apache.inlong.manager.common.pojo.group.InlongGroupRequest;
import org.apache.inlong.manager.common.pojo.sink.SinkForSortDTO;
import org.apache.inlong.manager.common.pojo.sink.hive.HiveSinkDTO;
import org.apache.inlong.manager.common.pojo.workflow.form.GroupResourceProcessForm;
import org.apache.inlong.manager.common.settings.InlongGroupSettings;
import org.apache.inlong.manager.common.util.JsonUtils;
import org.apache.inlong.manager.common.util.Preconditions;
import org.apache.inlong.manager.dao.entity.InlongGroupEntity;
import org.apache.inlong.manager.dao.entity.StreamSinkFieldEntity;
import org.apache.inlong.manager.dao.mapper.InlongGroupEntityMapper;
import org.apache.inlong.manager.dao.mapper.StreamSinkEntityMapper;
import org.apache.inlong.manager.dao.mapper.StreamSinkFieldEntityMapper;
import org.apache.inlong.manager.workflow.WorkflowContext;
import org.apache.inlong.manager.workflow.event.ListenerResult;
import org.apache.inlong.manager.workflow.event.task.SortOperateListener;
import org.apache.inlong.manager.workflow.event.task.TaskEvent;
import org.apache.inlong.sort.ZkTools;
import org.apache.inlong.sort.formats.common.FormatInfo;
import org.apache.inlong.sort.formats.common.TimestampFormatInfo;
import org.apache.inlong.sort.protocol.DataFlowInfo;
import org.apache.inlong.sort.protocol.FieldInfo;
import org.apache.inlong.sort.protocol.deserialization.DeserializationInfo;
import org.apache.inlong.sort.protocol.deserialization.InLongMsgCsvDeserializationInfo;
import org.apache.inlong.sort.protocol.sink.HiveSinkInfo;
import org.apache.inlong.sort.protocol.sink.HiveSinkInfo.HiveFileFormat;
import org.apache.inlong.sort.protocol.sink.HiveSinkInfo.HiveTimePartitionInfo;
import org.apache.inlong.sort.protocol.sink.SinkInfo;
import org.apache.inlong.sort.protocol.source.PulsarSourceInfo;
import org.apache.inlong.sort.protocol.source.SourceInfo;
import org.apache.inlong.sort.protocol.source.TubeSourceInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class PushHiveConfigTaskListener implements SortOperateListener {

    private static final Map<String, String> PARTITION_TIME_FORMAT_MAP = new HashMap<>();

    private static final Map<String, TimeUnit> PARTITION_TIME_UNIT_MAP = new HashMap<>();

    private static final String DATA_FLOW_GROUP_ID_KEY = "inlong.group.id";

    static {
        PARTITION_TIME_FORMAT_MAP.put("D", "yyyyMMdd");
        PARTITION_TIME_FORMAT_MAP.put("H", "yyyyMMddHH");
        PARTITION_TIME_FORMAT_MAP.put("I", "yyyyMMddHHmm");

        PARTITION_TIME_UNIT_MAP.put("D", TimeUnit.DAYS);
        PARTITION_TIME_UNIT_MAP.put("H", TimeUnit.HOURS);
        PARTITION_TIME_UNIT_MAP.put("I", TimeUnit.MINUTES);
    }

    @Autowired
    private ClusterBean clusterBean;
    @Autowired
    private InlongGroupEntityMapper groupMapper;
    @Autowired
    private StreamSinkEntityMapper sinkMapper;
    @Autowired
    private StreamSinkFieldEntityMapper streamSinkFieldMapper;

    @Override
    public TaskEvent event() {
        return TaskEvent.COMPLETE;
    }

    @Override
    public ListenerResult listen(WorkflowContext context) throws WorkflowListenerException {
        if (log.isDebugEnabled()) {
            log.debug("begin push hive config to sort, context={}", context);
        }

        GroupResourceProcessForm form = (GroupResourceProcessForm) context.getProcessForm();
        InlongGroupRequest groupInfo = form.getGroupInfo();
        String groupId = groupInfo.getInlongGroupId();

        InlongGroupEntity groupEntity = groupMapper.selectByIdentifier(groupId);
        if (groupEntity == null || EntityStatus.IS_DELETED.getCode().equals(groupEntity.getIsDeleted())) {
            log.warn("skip to push sort hive config for groupId={}, as biz not exists or has been deleted", groupId);
            return ListenerResult.success();
        }

        // if streamId not null, just push the config belongs to the groupId and the streamId
        String streamId = form.getInlongStreamId();
        List<SinkForSortDTO> sortInfoList = sinkMapper.selectAllConfig(groupId, streamId);
        for (SinkForSortDTO sortInfo : sortInfoList) {
            Integer sinkId = sortInfo.getId();

            if (log.isDebugEnabled()) {
                log.debug("hive sink info: {}", sortInfo);
            }

            DataFlowInfo dataFlowInfo = getDataFlowInfo(groupInfo, sortInfo);
            // add extra properties for flow info
            dataFlowInfo.getProperties().put(DATA_FLOW_GROUP_ID_KEY, groupId);
            if (log.isDebugEnabled()) {
                log.debug("try to push hive config to sort: {}", JsonUtils.toJson(dataFlowInfo));
            }
            try {
                String zkUrl = clusterBean.getZkUrl();
                String zkRoot = clusterBean.getZkRoot();
                // push data flow info to zk
                String sortClusterName = clusterBean.getAppName();
                ZkTools.updateDataFlowInfo(dataFlowInfo, sortClusterName, sinkId, zkUrl, zkRoot);
                // add sink id to zk
                ZkTools.addDataFlowToCluster(sortClusterName, sinkId, zkUrl, zkRoot);
            } catch (Exception e) {
                log.error("add or update inlong stream information to zk failed, sinkId={} ", sinkId, e);
                throw new WorkflowListenerException("push hive config to sort failed, reason: " + e.getMessage());
            }
        }

        return ListenerResult.success();
    }

    private DataFlowInfo getDataFlowInfo(InlongGroupRequest groupInfo, SinkForSortDTO sortInfo) {
        String groupId = sortInfo.getInlongGroupId();
        String streamId = sortInfo.getInlongStreamId();
        List<StreamSinkFieldEntity> fieldList = streamSinkFieldMapper.selectFields(groupId, streamId);

        if (fieldList == null || fieldList.size() == 0) {
            throw new WorkflowListenerException("no hive fields for groupId=" + groupId + ", streamId=" + streamId);
        }

        HiveSinkDTO hiveInfo = HiveSinkDTO.getFromJson(sortInfo.getExtParams());
        SourceInfo sourceInfo = getSourceInfo(groupInfo, sortInfo, hiveInfo, fieldList);
        SinkInfo sinkInfo = getSinkInfo(hiveInfo, fieldList);

        // push information
        return new DataFlowInfo(sortInfo.getId(), sourceInfo, sinkInfo);
    }

    private HiveSinkInfo getSinkInfo(HiveSinkDTO hiveInfo, List<StreamSinkFieldEntity> fieldList) {
        if (hiveInfo.getJdbcUrl() == null) {
            throw new WorkflowListenerException("hive server url cannot be empty");
        }

        // Use the field separator in Hive, the default is TextFile
        Character separator = (char) Integer.parseInt(hiveInfo.getDataSeparator());
        HiveFileFormat fileFormat;
        String format = hiveInfo.getFileFormat();

        if (Constant.FILE_FORMAT_ORC.equalsIgnoreCase(format)) {
            fileFormat = new HiveSinkInfo.OrcFileFormat(1000);
        } else if (Constant.FILE_FORMAT_SEQUENCE.equalsIgnoreCase(format)) {
            fileFormat = new HiveSinkInfo.SequenceFileFormat(separator, 100);
        } else if (Constant.FILE_FORMAT_PARQUET.equalsIgnoreCase(format)) {
            fileFormat = new HiveSinkInfo.ParquetFileFormat();
        } else {
            fileFormat = new HiveSinkInfo.TextFileFormat(separator);
        }

        // The primary partition field, in Sink must be HiveTimePartitionInfo
        List<HiveSinkInfo.HivePartitionInfo> partitionList = new ArrayList<>();
        String primary = hiveInfo.getPrimaryPartition();
        if (StringUtils.isNotEmpty(primary)) {
            // Hive partitions are by day, hour, and minute
            String unit = hiveInfo.getPartitionUnit();
            HiveTimePartitionInfo timePartitionInfo = new HiveTimePartitionInfo(
                    primary, PARTITION_TIME_FORMAT_MAP.get(unit));
            partitionList.add(timePartitionInfo);
        }
        // For the secondary partition field, the sink is temporarily encapsulated as HiveFieldPartitionInfo,
        // TODO the type be set according to the type of the field itself.
        if (StringUtils.isNotEmpty(hiveInfo.getSecondaryPartition())) {
            partitionList.add(new HiveSinkInfo.HiveFieldPartitionInfo(hiveInfo.getSecondaryPartition()));
        }

        // dataPath = hdfsUrl + / + warehouseDir + / + dbName + .db/ + tableName
        StringBuilder dataPathBuilder = new StringBuilder();
        String hdfsUrl = hiveInfo.getHdfsDefaultFs();
        String warehouseDir = hiveInfo.getWarehouseDir();
        if (hdfsUrl.endsWith("/")) {
            dataPathBuilder.append(hdfsUrl, 0, hdfsUrl.length() - 1);
        } else {
            dataPathBuilder.append(hdfsUrl);
        }
        if (warehouseDir.endsWith("/")) {
            dataPathBuilder.append(warehouseDir, 0, warehouseDir.length() - 1);
        } else {
            dataPathBuilder.append(warehouseDir);
        }
        String dataPath = dataPathBuilder.append("/").append(hiveInfo.getDbName())
                .append(".db/").append(hiveInfo.getTableName()).toString();

        // Get the sink field, if there is no partition field in the source field, add the partition field to the end
        List<FieldInfo> fieldInfoList = getSinkFields(fieldList, hiveInfo.getPrimaryPartition());

        return new HiveSinkInfo(fieldInfoList.toArray(new FieldInfo[0]), hiveInfo.getJdbcUrl(),
                hiveInfo.getDbName(), hiveInfo.getTableName(), hiveInfo.getUsername(), hiveInfo.getPassword(),
                dataPath, partitionList.toArray(new HiveSinkInfo.HivePartitionInfo[0]), fileFormat);
    }

    /**
     * Get source info
     */
    private SourceInfo getSourceInfo(InlongGroupRequest groupInfo, SinkForSortDTO sortInfo,
            HiveSinkDTO hiveInfo, List<StreamSinkFieldEntity> fieldList) {
        DeserializationInfo deserializationInfo = null;
        boolean isDbType = Constant.DATA_SOURCE_DB.equals(sortInfo.getDataSourceType());
        if (!isDbType) {
            // FILE and auto push source, the data format is TEXT or KEY-VALUE, temporarily use InLongMsgCsv
            String dataType = sortInfo.getDataType();
            if (Constant.DATA_TYPE_TEXT.equalsIgnoreCase(dataType)
                    || Constant.DATA_TYPE_KEY_VALUE.equalsIgnoreCase(dataType)) {
                // Use the field separator from the inlong stream
                char separator = (char) Integer.parseInt(sortInfo.getSourceSeparator());
                // TODO support escape
                /*Character escape = null;
                if (info.getDataEscapeChar() != null) {
                    escape = info.getDataEscapeChar().charAt(0);
                }*/
                // Whether to delete the first separator, the default is false for the time being
                deserializationInfo = new InLongMsgCsvDeserializationInfo(sortInfo.getInlongStreamId(), separator);
            }
        }

        // The number and order of the source fields must be the same as the target fields
        SourceInfo sourceInfo = null;
        // Get the source field, if there is no partition field in source, add the partition field to the end
        List<FieldInfo> sourceFields = getSourceFields(fieldList, hiveInfo.getPrimaryPartition());

        String middleWare = groupInfo.getMiddlewareType();
        if (Constant.MIDDLEWARE_TUBE.equalsIgnoreCase(middleWare)) {
            String masterAddress = clusterBean.getTubeMaster();
            Preconditions.checkNotNull(masterAddress, "tube cluster address cannot be empty");
            String topic = groupInfo.getMqResourceObj();
            // The consumer group name is: taskName_topicName_consumer_group
            String consumerGroup = clusterBean.getAppName() + "_" + topic + "_consumer_group";
            sourceInfo = new TubeSourceInfo(topic, masterAddress, consumerGroup,
                    deserializationInfo, sourceFields.toArray(new FieldInfo[0]));
        } else if (Constant.MIDDLEWARE_PULSAR.equalsIgnoreCase(middleWare)) {
            sourceInfo = createPulsarSourceInfo(groupInfo, sortInfo, deserializationInfo, sourceFields);
        }

        return sourceInfo;
    }

    /**
     * Get sink fields
     */
    private List<FieldInfo> getSinkFields(List<StreamSinkFieldEntity> fieldList, String partitionField) {
        boolean duplicate = false;
        List<FieldInfo> fieldInfoList = new ArrayList<>();
        for (StreamSinkFieldEntity field : fieldList) {
            String fieldName = field.getFieldName();
            if (fieldName.equals(partitionField)) {
                duplicate = true;
            }

            FormatInfo formatInfo = SortFieldFormatUtils.convertFieldFormat(field.getFieldType().toLowerCase());
            FieldInfo fieldInfo = new FieldInfo(fieldName, formatInfo);
            fieldInfoList.add(fieldInfo);
        }

        // There is no partition field in the ordinary field, you need to add the partition field to the end
        if (!duplicate && StringUtils.isNotEmpty(partitionField)) {
            FieldInfo fieldInfo = new FieldInfo(partitionField, new TimestampFormatInfo("MILLIS"));
            fieldInfoList.add(0, fieldInfo);
        }
        return fieldInfoList;
    }

    /**
     * Get source field list
     * TODO  support BuiltInField
     */
    private List<FieldInfo> getSourceFields(List<StreamSinkFieldEntity> fieldList, String partitionField) {
        List<FieldInfo> fieldInfoList = new ArrayList<>();
        for (StreamSinkFieldEntity field : fieldList) {
            FormatInfo formatInfo = SortFieldFormatUtils.convertFieldFormat(field.getSourceFieldType().toLowerCase());
            String fieldName = field.getSourceFieldName();

            FieldInfo fieldInfo = new FieldInfo(fieldName, formatInfo);
            fieldInfoList.add(fieldInfo);
        }

        return fieldInfoList;
    }

    private PulsarSourceInfo createPulsarSourceInfo(InlongGroupRequest groupInfo, SinkForSortDTO sortInfo,
            DeserializationInfo deserializationInfo, List<FieldInfo> sourceFields) {
        final String tenant = clusterBean.getDefaultTenant();
        final String namespace = groupInfo.getMqResourceObj();
        final String pulsarTopic = sortInfo.getMqResourceObj();
        // Full name of Topic in Pulsar
        final String fullTopicName = "persistent://" + tenant + "/" + namespace + "/" + pulsarTopic;
        final String consumerGroup = clusterBean.getAppName() + "_" + pulsarTopic + "_consumer_group";
        String adminUrl = null;
        String serviceUrl = null;
        String authentication = null;
        if (CollectionUtils.isNotEmpty(groupInfo.getExtList())) {
            for (InlongGroupExtInfo extInfo : groupInfo.getExtList()) {
                if (InlongGroupSettings.PULSAR_SERVICE_URL.equals(extInfo.getKeyName())
                        && StringUtils.isNotEmpty(extInfo.getKeyValue())) {
                    serviceUrl = extInfo.getKeyValue();
                }
                if (InlongGroupSettings.PULSAR_AUTHENTICATION.equals(extInfo.getKeyName())
                        && StringUtils.isNotEmpty(extInfo.getKeyValue())) {
                    authentication = extInfo.getKeyValue();
                }
                if (InlongGroupSettings.PULSAR_ADMIN_URL.equals(extInfo.getKeyName())
                        && StringUtils.isNotEmpty(extInfo.getKeyValue())) {
                    adminUrl = extInfo.getKeyValue();
                }
            }
        }
        if (StringUtils.isEmpty(adminUrl)) {
            adminUrl = clusterBean.getPulsarAdminUrl();
        }
        if (StringUtils.isEmpty(serviceUrl)) {
            serviceUrl = clusterBean.getPulsarServiceUrl();
        }
        return new PulsarSourceInfo(adminUrl, serviceUrl, fullTopicName, consumerGroup,
                deserializationInfo, sourceFields.toArray(new FieldInfo[0]), authentication);
    }

    @Override
    public boolean async() {
        return false;
    }

}