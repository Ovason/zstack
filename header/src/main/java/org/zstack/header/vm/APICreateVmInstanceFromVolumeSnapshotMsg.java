package org.zstack.header.vm;

import org.springframework.http.HttpMethod;
import org.zstack.header.cluster.ClusterVO;
import org.zstack.header.configuration.InstanceOfferingVO;
import org.zstack.header.host.HostVO;
import org.zstack.header.identity.Action;
import org.zstack.header.message.*;
import org.zstack.header.network.l3.L3NetworkVO;
import org.zstack.header.other.APIAuditor;
import org.zstack.header.rest.RestRequest;
import org.zstack.header.storage.primary.PrimaryStorageVO;
import org.zstack.header.storage.snapshot.VolumeSnapshotVO;
import org.zstack.header.tag.TagResourceType;
import org.zstack.header.zone.ZoneVO;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Created by MaJin on 2021/3/10.
 */
@TagResourceType(VmInstanceVO.class)
@Action(category = VmInstanceConstant.ACTION_CATEGORY)
@RestRequest(
        path = "/vm-instances/from/volume-snapshots/{volumeSnapshotUuid}",
        method = HttpMethod.POST,
        responseClass = APICreateVmInstanceFromVolumeSnapshotEvent.class,
        parameterName = "params"
)
@DefaultTimeout(timeunit = TimeUnit.HOURS, value = 72)
public class APICreateVmInstanceFromVolumeSnapshotMsg extends APICreateMessage implements NewVmInstanceMessage2, APIAuditor {
    @APIParam(maxLength = 255)
    private String name;
    /**
     * @desc max length of 255 characters
     * @optional
     */
    @APIParam(required = false, maxLength = 2048)
    private String description;
    /**
     * @desc uuid of instance offering. See :ref:`InstanceOfferingInventory`
     */
    @APIParam(resourceType = InstanceOfferingVO.class, checkAccount = true, required = false)
    private String instanceOfferingUuid;

    @APIParam(required = false)
    private Integer cpuNum;

    @APIParam(required = false)
    private Long memorySize;

    /**
     * @desc a list of L3Network uuid the vm will create nic on. See :ref:`L3NetworkInventory`
     */
    @APIParam(resourceType = L3NetworkVO.class, nonempty = true, checkAccount = true)
    private List<String> l3NetworkUuids;
    /**
     * @desc see type of :ref:`VmInstanceInventory`
     * @choices - UserVm
     * - ApplianceVm
     */
    @APIParam(validValues = {"UserVm", "ApplianceVm"}, required = false)
    private String type;
    /**
     * @desc root volume. Optional when vm is created from RootVolumeTemplate,
     * mandatory when vm is created from ISO. See 'mediaType' of :ref:`ImageInventory`
     */
    @APIParam(resourceType = VolumeSnapshotVO.class, checkAccount = true)
    private String volumeSnapshotUuid;

    @APIParam(required = false, validValues = {"Linux", "Windows", "Other", "Paravirtualization", "WindowsVirtio"})
    private String platform;

    @APIParam(required = false, resourceType = ZoneVO.class)
    private String zoneUuid;
    /**
     * @desc when not null, vm will be created in the cluster this uuid specified
     * @optional
     */
    @APIParam(required = false, resourceType = ClusterVO.class)
    private String clusterUuid;
    /**
     * @desc when not null, vm will be created on the host this uuid specified
     * @optional
     */
    @APIParam(required = false, resourceType = HostVO.class)
    private String hostUuid;

    /**
     * @desc when not null, vm will be created on the primary storage this uuid specified
     * @optional
     */
    @APIParam(required = false, resourceType = PrimaryStorageVO.class)
    private String primaryStorageUuid;

    private String defaultL3NetworkUuid;

    @APIParam(required = false, validValues = {"InstantStart", "CreateStopped"})
    private String strategy = VmCreationStrategy.InstantStart.toString();

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getInstanceOfferingUuid() {
        return instanceOfferingUuid;
    }

    public void setInstanceOfferingUuid(String instanceOfferingUuid) {
        this.instanceOfferingUuid = instanceOfferingUuid;
    }

    public Integer getCpuNum() {
        return cpuNum;
    }

    public void setCpuNum(Integer cpuNum) {
        this.cpuNum = cpuNum;
    }

    public Long getMemorySize() {
        return memorySize;
    }

    @Override
    public String getZoneUuid() {
        return zoneUuid;
    }

    public void setMemorySize(Long memorySize) {
        this.memorySize = memorySize;
    }

    public List<String> getL3NetworkUuids() {
        return l3NetworkUuids;
    }

    public void setL3NetworkUuids(List<String> l3NetworkUuids) {
        this.l3NetworkUuids = l3NetworkUuids;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getVolumeSnapshotUuid() {
        return volumeSnapshotUuid;
    }

    public void setVolumeSnapshotUuid(String volumeSnapshotUuid) {
        this.volumeSnapshotUuid = volumeSnapshotUuid;
    }

    public String getClusterUuid() {
        return clusterUuid;
    }

    public void setClusterUuid(String clusterUuid) {
        this.clusterUuid = clusterUuid;
    }

    @Override
    public void setZoneUuid(String zoneUuid) {
        this.zoneUuid = zoneUuid;
    }

    public String getHostUuid() {
        return hostUuid;
    }

    public void setHostUuid(String hostUuid) {
        this.hostUuid = hostUuid;
    }

    public String getDefaultL3NetworkUuid() {
        return defaultL3NetworkUuid;
    }

    public void setDefaultL3NetworkUuid(String defaultL3NetworkUuid) {
        this.defaultL3NetworkUuid = defaultL3NetworkUuid;
    }

    public String getStrategy() {
        return strategy;
    }

    public void setStrategy(String strategy) {
        this.strategy = strategy;
    }

    public String getPlatform() {
        return platform;
    }

    public void setPlatform(String platform) {
        this.platform = platform;
    }

    public String getPrimaryStorageUuid() {
        return primaryStorageUuid;
    }

    public void setPrimaryStorageUuid(String primaryStorageUuid) {
        this.primaryStorageUuid = primaryStorageUuid;
    }

    public static APICreateVmInstanceFromVolumeSnapshotMsg __example__() {
        APICreateVmInstanceFromVolumeSnapshotMsg msg = new APICreateVmInstanceFromVolumeSnapshotMsg();
        msg.setName("vm1");
        msg.setDescription("this is a vm");
        msg.setClusterUuid(uuid());
        msg.setVolumeSnapshotUuid(uuid());
        msg.setInstanceOfferingUuid(uuid());
        msg.setL3NetworkUuids(Collections.singletonList(uuid()));
        return msg;
    }

    @Override
    public APIAuditor.Result audit(APIMessage msg, APIEvent rsp) {
        return new APIAuditor.Result(rsp.isSuccess() ? ((APICreateVmInstanceFromVolumeSnapshotEvent)rsp).getInventory().getUuid() : "", VmInstanceVO.class);
    }
}
