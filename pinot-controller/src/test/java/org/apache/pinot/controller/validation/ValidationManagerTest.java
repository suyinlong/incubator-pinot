/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pinot.controller.validation;

import java.util.ArrayList;
import java.util.List;
import org.apache.helix.HelixAdmin;
import org.apache.helix.HelixManager;
import org.apache.helix.manager.zk.ZkClient;
import org.apache.helix.model.IdealState;
import org.apache.helix.model.InstanceConfig;
import org.apache.pinot.common.config.TableConfig;
import org.apache.pinot.common.config.TagNameUtils;
import org.apache.pinot.common.metadata.segment.OfflineSegmentZKMetadata;
import org.apache.pinot.common.metadata.segment.RealtimeSegmentZKMetadata;
import org.apache.pinot.common.segment.SegmentMetadata;
import org.apache.pinot.common.utils.CommonConstants;
import org.apache.pinot.common.utils.HLCSegmentName;
import org.apache.pinot.common.utils.LLCSegmentName;
import org.apache.pinot.common.utils.ZkStarter;
import org.apache.pinot.common.utils.helix.HelixHelper;
import org.apache.pinot.controller.helix.ControllerRequestBuilderUtil;
import org.apache.pinot.controller.helix.core.PinotHelixResourceManager;
import org.apache.pinot.controller.helix.core.util.HelixSetupUtils;
import org.apache.pinot.controller.utils.SegmentMetadataMockUtils;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.joda.time.Interval;
import org.mockito.Mockito;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;


/**
 * Tests for the ValidationManagers.
 */
public class ValidationManagerTest {
  private String HELIX_CLUSTER_NAME = "TestValidationManager";

  private static final String ZK_STR = ZkStarter.DEFAULT_ZK_STR;
  private static final String CONTROLLER_INSTANCE_NAME = "localhost_11984";
  private static final String TEST_TABLE_NAME = "testTable";
  private static final String TEST_TABLE_TWO = "testTable2";
  private static final String TEST_SEGMENT_NAME = "testSegment";

  private ZkClient _zkClient;

  private PinotHelixResourceManager _pinotHelixResourceManager;
  private ZkStarter.ZookeeperInstance _zookeeperInstance;
  private TableConfig _offlineTableConfig;
  private HelixManager _helixManager;

  @BeforeClass
  public void setUp()
      throws Exception {
    _zookeeperInstance = ZkStarter.startLocalZkServer();
    _zkClient = new ZkClient(ZK_STR);
    Thread.sleep(1000);

    final boolean enableBatchMessageMode = true;
    _pinotHelixResourceManager =
        new PinotHelixResourceManager(ZkStarter.DEFAULT_ZK_STR, HELIX_CLUSTER_NAME, null, 1000L,
            true, enableBatchMessageMode);
    HelixManager helixZkManager = HelixSetupUtils
        .setup(HELIX_CLUSTER_NAME, ZK_STR, CONTROLLER_INSTANCE_NAME, false, enableBatchMessageMode);
    Assert.assertNotNull(helixZkManager);
    _pinotHelixResourceManager.start(helixZkManager);

    ControllerRequestBuilderUtil.addFakeDataInstancesToAutoJoinHelixCluster(HELIX_CLUSTER_NAME, ZK_STR, 2, true);
    ControllerRequestBuilderUtil.addFakeBrokerInstancesToAutoJoinHelixCluster(HELIX_CLUSTER_NAME, ZK_STR, 2, true);

    _offlineTableConfig =
        new TableConfig.Builder(CommonConstants.Helix.TableType.OFFLINE).setTableName(TEST_TABLE_NAME).setNumReplicas(2)
            .build();

    final String instanceId = "localhost_helixController";
    _helixManager = HelixSetupUtils.setup(HELIX_CLUSTER_NAME, ZK_STR, instanceId, /*isUpdateStateModel=*/false, true);
    _pinotHelixResourceManager.addTable(_offlineTableConfig);
  }

  @Test
  public void testRebuildBrokerResourceWhenBrokerAdded()
      throws Exception {
    // Check that the first table we added doesn't need to be rebuilt(case where ideal state brokers and brokers in broker resource are the same.
    String partitionName = _offlineTableConfig.getTableName();
    HelixAdmin helixAdmin = _helixManager.getClusterManagmentTool();

    IdealState idealState = HelixHelper.getBrokerIdealStates(helixAdmin, HELIX_CLUSTER_NAME);
    // Ensure that the broker resource is not rebuilt.
    Assert.assertTrue(idealState.getInstanceSet(partitionName)
        .equals(_pinotHelixResourceManager.getAllInstancesForBrokerTenant(TagNameUtils.DEFAULT_TENANT_NAME)));
    _pinotHelixResourceManager.rebuildBrokerResourceFromHelixTags(partitionName);

    // Add another table that needs to be rebuilt
    TableConfig offlineTableConfigTwo =
        new TableConfig.Builder(CommonConstants.Helix.TableType.OFFLINE).setTableName(TEST_TABLE_TWO).build();
    _pinotHelixResourceManager.addTable(offlineTableConfigTwo);
    String partitionNameTwo = offlineTableConfigTwo.getTableName();

    // Add a new broker manually such that the ideal state is not updated and ensure that rebuild broker resource is called
    final String brokerId = "Broker_localhost_2";
    InstanceConfig instanceConfig = new InstanceConfig(brokerId);
    instanceConfig.setInstanceEnabled(true);
    instanceConfig.setHostName("Broker_localhost");
    instanceConfig.setPort("2");
    helixAdmin.addInstance(HELIX_CLUSTER_NAME, instanceConfig);
    helixAdmin.addInstanceTag(HELIX_CLUSTER_NAME, instanceConfig.getInstanceName(),
        TagNameUtils.getBrokerTagForTenant(TagNameUtils.DEFAULT_TENANT_NAME));
    idealState = HelixHelper.getBrokerIdealStates(helixAdmin, HELIX_CLUSTER_NAME);
    // Assert that the two don't equal before the call to rebuild the broker resource.
    Assert.assertTrue(!idealState.getInstanceSet(partitionNameTwo)
        .equals(_pinotHelixResourceManager.getAllInstancesForBrokerTenant(TagNameUtils.DEFAULT_TENANT_NAME)));
    _pinotHelixResourceManager.rebuildBrokerResourceFromHelixTags(partitionNameTwo);
    idealState = HelixHelper.getBrokerIdealStates(helixAdmin, HELIX_CLUSTER_NAME);
    // Assert that the two do equal after being rebuilt.
    Assert.assertTrue(idealState.getInstanceSet(partitionNameTwo)
        .equals(_pinotHelixResourceManager.getAllInstancesForBrokerTenant(TagNameUtils.DEFAULT_TENANT_NAME)));
  }

  @Test
  public void testPushTimePersistence()
      throws Exception {
    SegmentMetadata segmentMetadata = SegmentMetadataMockUtils.mockSegmentMetadata(TEST_TABLE_NAME, TEST_SEGMENT_NAME);

    _pinotHelixResourceManager.addNewSegment(segmentMetadata, "http://dummy/");
    OfflineSegmentZKMetadata offlineSegmentZKMetadata =
        _pinotHelixResourceManager.getOfflineSegmentZKMetadata(TEST_TABLE_NAME, TEST_SEGMENT_NAME);
    long pushTime = offlineSegmentZKMetadata.getPushTime();
    // Check that the segment has been pushed in the last 30 seconds
    Assert.assertTrue(System.currentTimeMillis() - pushTime < 30_000);
    // Check that there is no refresh time
    assertEquals(offlineSegmentZKMetadata.getRefreshTime(), Long.MIN_VALUE);

    // Refresh the segment
    Mockito.when(segmentMetadata.getCrc()).thenReturn(Long.toString(System.nanoTime()));
    _pinotHelixResourceManager.refreshSegment(segmentMetadata, offlineSegmentZKMetadata);

    offlineSegmentZKMetadata =
        _pinotHelixResourceManager.getOfflineSegmentZKMetadata(TEST_TABLE_NAME, TEST_SEGMENT_NAME);
    // Check that the segment still has the same push time
    assertEquals(offlineSegmentZKMetadata.getPushTime(), pushTime);
    // Check that the refresh time is in the last 30 seconds
    Assert.assertTrue(System.currentTimeMillis() - offlineSegmentZKMetadata.getRefreshTime() < 30_000L);
  }

  @Test
  public void testTotalDocumentCountRealTime()
      throws Exception {
    // Create a bunch of dummy segments
    final String group1 = TEST_TABLE_NAME + "_REALTIME_1466446700000_34";
    final String group2 = TEST_TABLE_NAME + "_REALTIME_1466446700000_17";
    String segmentName1 = new HLCSegmentName(group1, "0", "1").getSegmentName();
    String segmentName2 = new HLCSegmentName(group1, "0", "2").getSegmentName();
    String segmentName3 = new HLCSegmentName(group1, "0", "3").getSegmentName();
    String segmentName4 = new HLCSegmentName(group2, "0", "3").getSegmentName();

    List<RealtimeSegmentZKMetadata> segmentZKMetadataList = new ArrayList<>();
    segmentZKMetadataList
        .add(SegmentMetadataMockUtils.mockRealtimeSegmentZKMetadata(TEST_TABLE_NAME, segmentName1, 10));
    segmentZKMetadataList
        .add(SegmentMetadataMockUtils.mockRealtimeSegmentZKMetadata(TEST_TABLE_NAME, segmentName2, 20));
    segmentZKMetadataList
        .add(SegmentMetadataMockUtils.mockRealtimeSegmentZKMetadata(TEST_TABLE_NAME, segmentName3, 30));
    // This should get ignored in the count as it belongs to a different group id
    segmentZKMetadataList
        .add(SegmentMetadataMockUtils.mockRealtimeSegmentZKMetadata(TEST_TABLE_NAME, segmentName4, 20));

    assertEquals(RealtimeSegmentValidationManager.computeRealtimeTotalDocumentInSegments(segmentZKMetadataList, true),
        60);

    // Now add some low level segment names
    String segmentName5 = new LLCSegmentName(TEST_TABLE_NAME, 1, 0, 1000).getSegmentName();
    String segmentName6 = new LLCSegmentName(TEST_TABLE_NAME, 2, 27, 10000).getSegmentName();
    segmentZKMetadataList
        .add(SegmentMetadataMockUtils.mockRealtimeSegmentZKMetadata(TEST_TABLE_NAME, segmentName5, 10));
    segmentZKMetadataList.add(SegmentMetadataMockUtils.mockRealtimeSegmentZKMetadata(TEST_TABLE_NAME, segmentName6, 5));

    // Only the LLC segments should get counted.
    assertEquals(RealtimeSegmentValidationManager.computeRealtimeTotalDocumentInSegments(segmentZKMetadataList, false),
        15);
  }

  @AfterClass
  public void shutDown() {
    _pinotHelixResourceManager.stop();
    _zkClient.close();
    ZkStarter.stopLocalZkServer(_zookeeperInstance);
  }

  @Test
  public void testComputeNumMissingSegments() {
    Interval jan1st = new Interval(new DateTime(2015, 1, 1, 0, 0, 0), new DateTime(2015, 1, 1, 23, 59, 59));
    Interval jan2nd = new Interval(new DateTime(2015, 1, 2, 0, 0, 0), new DateTime(2015, 1, 2, 23, 59, 59));
    Interval jan3rd = new Interval(new DateTime(2015, 1, 3, 0, 0, 0), new DateTime(2015, 1, 3, 23, 59, 59));
    Interval jan4th = new Interval(new DateTime(2015, 1, 4, 0, 0, 0), new DateTime(2015, 1, 4, 23, 59, 59));
    Interval jan5th = new Interval(new DateTime(2015, 1, 5, 0, 0, 0), new DateTime(2015, 1, 5, 23, 59, 59));

    ArrayList<Interval> jan1st2nd3rd = new ArrayList<>();
    jan1st2nd3rd.add(jan1st);
    jan1st2nd3rd.add(jan2nd);
    jan1st2nd3rd.add(jan3rd);
    assertEquals(OfflineSegmentIntervalChecker.computeNumMissingSegments(jan1st2nd3rd, Duration.standardDays(1)), 0);

    ArrayList<Interval> jan1st2nd3rd5th = new ArrayList<>(jan1st2nd3rd);
    jan1st2nd3rd5th.add(jan5th);
    assertEquals(OfflineSegmentIntervalChecker.computeNumMissingSegments(jan1st2nd3rd5th, Duration.standardDays(1)), 1);

    // Should also work if the intervals are in random order
    ArrayList<Interval> jan5th2nd1st = new ArrayList<>();
    jan5th2nd1st.add(jan5th);
    jan5th2nd1st.add(jan2nd);
    jan5th2nd1st.add(jan1st);
    assertEquals(OfflineSegmentIntervalChecker.computeNumMissingSegments(jan5th2nd1st, Duration.standardDays(1)), 2);

    // Should also work if the intervals are of different sizes
    Interval jan1stAnd2nd = new Interval(new DateTime(2015, 1, 1, 0, 0, 0), new DateTime(2015, 1, 2, 23, 59, 59));
    ArrayList<Interval> jan1st2nd4th5th = new ArrayList<Interval>();
    jan1st2nd4th5th.add(jan1stAnd2nd);
    jan1st2nd4th5th.add(jan4th);
    jan1st2nd4th5th.add(jan5th);
    assertEquals(OfflineSegmentIntervalChecker.computeNumMissingSegments(jan1st2nd4th5th, Duration.standardDays(1)), 1);
  }
}
