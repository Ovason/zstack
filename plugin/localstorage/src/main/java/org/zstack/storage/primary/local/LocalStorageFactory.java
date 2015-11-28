package org.zstack.storage.primary.local;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.zstack.compute.vm.VmAllocatePrimaryStorageFlow;
import org.zstack.compute.vm.VmAllocatePrimaryStorageForAttachingDiskFlow;
import org.zstack.compute.vm.VmMigrateOnHypervisorFlow;
import org.zstack.core.cloudbus.CloudBus;
import org.zstack.core.cloudbus.CloudBusCallBack;
import org.zstack.core.cloudbus.CloudBusListCallBack;
import org.zstack.core.componentloader.PluginRegistry;
import org.zstack.core.db.DatabaseFacade;
import org.zstack.core.db.SimpleQuery;
import org.zstack.core.db.SimpleQuery.Op;
import org.zstack.core.errorcode.ErrorFacade;
import org.zstack.header.Component;
import org.zstack.header.core.FutureCompletion;
import org.zstack.header.core.workflow.Flow;
import org.zstack.header.core.workflow.FlowChain;
import org.zstack.header.errorcode.ErrorCode;
import org.zstack.header.errorcode.OperationFailureException;
import org.zstack.header.exception.CloudRuntimeException;
import org.zstack.header.host.*;
import org.zstack.header.message.MessageReply;
import org.zstack.header.query.AddExpandedQueryExtensionPoint;
import org.zstack.header.query.ExpandedQueryAliasStruct;
import org.zstack.header.query.ExpandedQueryStruct;
import org.zstack.header.storage.primary.*;
import org.zstack.header.storage.snapshot.VolumeSnapshotInventory;
import org.zstack.header.vm.*;
import org.zstack.header.vm.VmInstanceConstant.VmOperation;
import org.zstack.header.volume.*;
import org.zstack.kvm.KVMConstant;
import org.zstack.utils.CollectionUtils;
import org.zstack.utils.TimeUtils;
import org.zstack.utils.Utils;
import org.zstack.utils.function.Function;
import org.zstack.utils.logging.CLogger;

import javax.persistence.LockModeType;
import javax.persistence.Tuple;
import javax.persistence.TypedQuery;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import static org.zstack.utils.CollectionDSL.list;

/**
 * Created by frank on 6/30/2015.
 */
public class LocalStorageFactory implements PrimaryStorageFactory, Component,
        MarshalVmOperationFlowExtensionPoint, HostDeleteExtensionPoint, VmAttachVolumeExtensionPoint,
        GetAttachableVolumeExtensionPoint, RecalculatePrimaryStorageCapacityExtensionPoint, HostMaintenancePolicyExtensionPoint,
        VolumeDeletionExtensionPoint, AddExpandedQueryExtensionPoint, VolumeGetAttachableVmExtensionPoint, RecoverDataVolumeExtensionPoint,
        RecoverVmExtensionPoint {
    private final static CLogger logger = Utils.getLogger(LocalStorageFactory.class);
    public static PrimaryStorageType type = new PrimaryStorageType(LocalStorageConstants.LOCAL_STORAGE_TYPE);

    @Autowired
    private DatabaseFacade dbf;
    @Autowired
    private PluginRegistry pluginRgty;
    @Autowired
    private CloudBus bus;
    @Autowired
    private ErrorFacade errf;
    @Autowired
    private PrimaryStorageOverProvisioningManager ratioMgr;

    private Map<String, LocalStorageBackupStorageMediator> backupStorageMediatorMap = new HashMap<String, LocalStorageBackupStorageMediator>();

    @Override
    public PrimaryStorageType getPrimaryStorageType() {
        return type;
    }

    @Override
    public String getPrimaryStorageTypeForRecalculateCapacityExtensionPoint() {
        return type.toString();
    }

    @Override
    @Transactional
    public void afterRecalculatePrimaryStorageCapacity(RecalculatePrimaryStorageCapacityStruct struct) {
        String psUuid = struct.getPrimaryStorageUuid();
        Map<String, Long> hostCap = new HashMap<String, Long>();

        String sql = "select sum(vol.size), ref.hostUuid from VolumeVO vol, LocalStorageResourceRefVO ref" +
                " where vol.primaryStorageUuid = :psUuid and vol.uuid = ref.resourceUuid and ref.primaryStorageUuid = vol.primaryStorageUuid group by ref.hostUuid";
        TypedQuery<Tuple> q = dbf.getEntityManager().createQuery(sql, Tuple.class);
        q.setParameter("psUuid", psUuid);
        List<Tuple> ts = q.getResultList();
        for (Tuple t : ts) {
            if (t.get(0, Long.class) == null) {
                // no volume
                continue;
            }

            long cap = t.get(0, Long.class);
            String hostUuid = t.get(1, String.class);
            hostCap.put(hostUuid, ratioMgr.calculateByRatio(psUuid, cap));
        }

        // hmm, in some case, the mysql returns duplicate hostUuid
        // I didn't figure out how. So use a group by to remove the duplicate
        sql = "select ref.hostUuid from LocalStorageResourceRefVO ref where ref.primaryStorageUuid = :psUuid group by ref.hostUuid";
        TypedQuery<String> hq = dbf.getEntityManager().createQuery(sql, String.class);
        hq.setParameter("psUuid", psUuid);
        List<String> huuids = hq.getResultList();

        for (String huuid : huuids) {
            // note: templates in image cache are physical size
            // do not calculate over provisioning for them
            sql = "select sum(i.size) from ImageCacheVO i where i.installUrl like :mark and i.primaryStorageUuid = :psUuid group by i.primaryStorageUuid";
            TypedQuery<Long> iq = dbf.getEntityManager().createQuery(sql, Long.class);
            iq.setParameter("psUuid", psUuid);
            iq.setParameter("mark", String.format("%%hostUuid://%s%%", huuid));
            Long isize = iq.getSingleResult();
            if (isize != null) {
                Long ncap = hostCap.get(huuid);
                ncap = ncap == null ? isize : ncap + isize;
                hostCap.put(huuid, ncap);
            }
        }

        for (Map.Entry<String, Long> e : hostCap.entrySet()) {
            String hostUuid = e.getKey();
            long used = e.getValue();

            LocalStorageHostRefVO ref = dbf.getEntityManager().find(LocalStorageHostRefVO.class, hostUuid, LockModeType.PESSIMISTIC_WRITE);
            long old = ref.getAvailableCapacity();
            long avail = ref.getTotalCapacity() - used - ref.getSystemUsedCapacity();
            ref.setAvailableCapacity(avail);
            dbf.getEntityManager().merge(ref);
            logger.debug(String.format("re-calculated available capacity[before:%s, now: %s] of host[uuid:%s] of the local storage[uuid:%s] with" +
                            " over-provisioning ratio[%s]", old, avail, hostUuid, psUuid, ratioMgr.getRatio(psUuid)));
        }
    }

    @Override
    public PrimaryStorageInventory createPrimaryStorage(PrimaryStorageVO vo, APIAddPrimaryStorageMsg msg) {
        vo.setMountPath(msg.getUrl());
        vo = dbf.persistAndRefresh(vo);
        return PrimaryStorageInventory.valueOf(vo);
    }

    @Override
    public PrimaryStorage getPrimaryStorage(PrimaryStorageVO vo) {
        return new LocalStorageBase(vo);
    }

    @Override
    public PrimaryStorageInventory getInventory(String uuid) {
        return PrimaryStorageInventory.valueOf(dbf.findByUuid(uuid, PrimaryStorageVO.class));
    }

    private String makeMediatorKey(String hvType, String bsType) {
        return hvType + "-" + bsType;
    }

    public LocalStorageBackupStorageMediator getBackupStorageMediator(String hvType, String bsType) {
        LocalStorageBackupStorageMediator m = backupStorageMediatorMap.get(makeMediatorKey(hvType, bsType));
        if (m == null) {
            throw new CloudRuntimeException(String.format("no LocalStorageBackupStorageMediator supporting hypervisor[%s] and backup storage[%s] ",
                    hvType, bsType));
        }

        return m;
    }

    @Override
    public boolean start() {
        for (LocalStorageBackupStorageMediator m : pluginRgty.getExtensionList(LocalStorageBackupStorageMediator.class)) {
            for (HypervisorType hvType : m.getSupportedHypervisorTypes()) {
                String key = makeMediatorKey(hvType.toString(), m.getSupportedBackupStorageType().toString());
                LocalStorageBackupStorageMediator old = backupStorageMediatorMap.get(key);
                if (old != null) {
                    throw new CloudRuntimeException(String.format("duplicate LocalStorageBackupStorageMediator[%s, %s] for hypervisor type[%s] and backup storage type[%s]",
                            m, old, hvType, m.getSupportedBackupStorageType()));
                }

                backupStorageMediatorMap.put(key, m);
            }
        }

        return true;
    }

    @Override
    public boolean stop() {
        return true;
    }

    @Transactional(readOnly = true)
    private String getLocalStorageInCluster(String clusterUuid) {
        String sql = "select pri.uuid from PrimaryStorageVO pri, PrimaryStorageClusterRefVO ref where pri.uuid = ref.primaryStorageUuid and ref.clusterUuid = :cuuid and pri.type = :ptype";
        TypedQuery<String> q = dbf.getEntityManager().createQuery(sql, String.class);
        q.setParameter("cuuid", clusterUuid);
        q.setParameter("ptype", LocalStorageConstants.LOCAL_STORAGE_TYPE);
        List<String> ret = q.getResultList();
        if (ret.isEmpty()) {
            return null;
        }

        return ret.get(0);
    }

    private boolean isRootVolumeOnLocalStorage(String rootVolumeUuid) {
        SimpleQuery<LocalStorageResourceRefVO> q = dbf.createQuery(LocalStorageResourceRefVO.class);
        q.add(LocalStorageResourceRefVO_.resourceUuid, Op.EQ, rootVolumeUuid);
        return q.isExists();
    }

    @Override
    public Flow marshalVmOperationFlow(String previousFlowName, String nextFlowName, FlowChain chain, VmInstanceSpec spec) {
        if (VmAllocatePrimaryStorageFlow.class.getName().equals(nextFlowName)) {
            if (spec.getCurrentVmOperation() == VmOperation.NewCreate) {
                if (getLocalStorageInCluster(spec.getDestHost().getClusterUuid()) != null) {
                    return new LocalStorageAllocateCapacityFlow();
                }
            }
        } else if (spec.getCurrentVmOperation() == VmOperation.AttachVolume) {
            VolumeInventory volume = spec.getDestDataVolumes().get(0);
            if (VolumeStatus.NotInstantiated.toString().equals(volume.getStatus()) && VmAllocatePrimaryStorageForAttachingDiskFlow.class.getName().equals(nextFlowName)) {
                if (isRootVolumeOnLocalStorage(spec.getVmInventory().getRootVolumeUuid())) {
                    return new LocalStorageAllocateCapacityForAttachingVolumeFlow();
                }
            }
        } else if (spec.getCurrentVmOperation() == VmOperation.Migrate && isRootVolumeOnLocalStorage(spec.getVmInventory().getRootVolumeUuid())
                && VmMigrateOnHypervisorFlow.class.getName().equals(nextFlowName)) {
            if (KVMConstant.KVM_HYPERVISOR_TYPE.equals(spec.getVmInventory().getHypervisorType())) {
                return new LocalStorageKvmMigrateVmFlow();
            } else {
                throw new OperationFailureException(errf.stringToOperationError(
                        String.format("local storage doesn't support live migration for hypervisor[%s]", spec.getVmInventory().getHypervisorType())
                ));
            }
        }

        return null;
    }

    @Override
    public void preDeleteHost(HostInventory inventory) throws HostException {
    }

    @Override
    public void beforeDeleteHost(final HostInventory inventory) {
        SimpleQuery<LocalStorageHostRefVO> q = dbf.createQuery(LocalStorageHostRefVO.class);
        q.select(LocalStorageHostRefVO_.primaryStorageUuid);
        q.add(LocalStorageHostRefVO_.hostUuid, Op.EQ, inventory.getUuid());
        final String psUuid = q.findValue();
        if (psUuid == null) {
            return;
        }

        logger.debug(String.format("the host[uuid:%s] belongs to the local storage[uuid:%s], starts to delete vms and" +
                " volumes on the host", inventory.getUuid(), psUuid));

        final List<String> vmUuids = new Callable<List<String>>() {
            @Override
            @Transactional(readOnly = true)
            public List<String> call() {
                String sql = "select vm.uuid from VolumeVO vol, LocalStorageResourceRefVO ref, VmInstanceVO vm where ref.primaryStorageUuid = :psUuid" +
                        " and vol.type = :vtype and ref.resourceUuid = vol.uuid and ref.resourceType = :rtype and ref.hostUuid = :huuid" +
                        " and vm.uuid = vol.vmInstanceUuid";
                TypedQuery<String> q = dbf.getEntityManager().createQuery(sql, String.class);
                q.setParameter("vtype", VolumeType.Root);
                q.setParameter("rtype", VolumeVO.class.getSimpleName());
                q.setParameter("huuid", inventory.getUuid());
                q.setParameter("psUuid", psUuid);
                return q.getResultList();
            }
        }.call();

        // destroy vms
        if (!vmUuids.isEmpty()) {
            List<DestroyVmInstanceMsg> msgs = CollectionUtils.transformToList(vmUuids, new Function<DestroyVmInstanceMsg, String>() {
                @Override
                public DestroyVmInstanceMsg call(String uuid) {
                    DestroyVmInstanceMsg msg = new DestroyVmInstanceMsg();
                    msg.setVmInstanceUuid(uuid);
                    bus.makeTargetServiceIdByResourceUuid(msg, VmInstanceConstant.SERVICE_ID, uuid);
                    return msg;
                }
            });

            final FutureCompletion completion = new FutureCompletion();
            bus.send(msgs, new CloudBusListCallBack(completion) {
                @Override
                public void run(List<MessageReply> replies) {
                    for (MessageReply r : replies){
                        if (!r.isSuccess()) {
                            String vmUuid = vmUuids.get(replies.indexOf(r));
                            //TODO
                            logger.warn(String.format("failed to destroy the vm[uuid:%s], %s", vmUuid, r.getError()));
                        }
                    }

                    completion.success();
                }
            });

            completion.await(TimeUnit.MINUTES.toMillis(15));
        }

        final List<String> volUuids = new Callable<List<String>>() {
            @Override
            @Transactional(readOnly = true)
            public List<String> call() {
                String sql = "select vol.uuid from VolumeVO vol, LocalStorageResourceRefVO ref where ref.primaryStorageUuid = :psUuid" +
                        " and vol.type = :vtype and ref.resourceUuid = vol.uuid and ref.resourceType = :rtype and ref.hostUuid = :huuid";
                TypedQuery<String> q = dbf.getEntityManager().createQuery(sql, String.class);
                q.setParameter("psUuid", psUuid);
                q.setParameter("vtype", VolumeType.Data);
                q.setParameter("rtype", VolumeVO.class.getSimpleName());
                q.setParameter("huuid", inventory.getUuid());
                return q.getResultList();
            }
        }.call();

        // delete data volumes
        if (!volUuids.isEmpty()) {
            List<DeleteVolumeMsg> msgs = CollectionUtils.transformToList(volUuids, new Function<DeleteVolumeMsg, String>() {
                @Override
                public DeleteVolumeMsg call(String uuid) {
                    DeleteVolumeMsg msg = new DeleteVolumeMsg();
                    msg.setUuid(uuid);
                    msg.setDetachBeforeDeleting(true);
                    bus.makeTargetServiceIdByResourceUuid(msg, VolumeConstant.SERVICE_ID, uuid);
                    return msg;
                }
            });

            final FutureCompletion completion = new FutureCompletion();
            bus.send(msgs, new CloudBusListCallBack(completion) {
                @Override
                public void run(List<MessageReply> replies) {
                    for (MessageReply r : replies) {
                        if (!r.isSuccess()) {
                            String uuid = volUuids.get(replies.indexOf(r));
                            //TODO
                            logger.warn(String.format("failed to delete the data volume[uuid:%s], %s", uuid,
                                    r.getError()));
                        }
                    }

                    completion.success();
                }
            });

            completion.await(TimeUnit.MINUTES.toMillis(15));
        }
    }

    @Override
    public void afterDeleteHost(final HostInventory inventory) {
        final String priUuid = getLocalStorageInCluster(inventory.getClusterUuid());
        if (priUuid != null) {
            RemoveHostFromLocalStorageMsg msg = new RemoveHostFromLocalStorageMsg();
            msg.setPrimaryStorageUuid(priUuid);
            msg.setHostUuid(inventory.getUuid());
            bus.makeTargetServiceIdByResourceUuid(msg, PrimaryStorageConstant.SERVICE_ID, priUuid);
            bus.send(msg, new CloudBusCallBack() {
                @Override
                public void run(MessageReply reply) {
                    if (!reply.isSuccess()) {
                        logger.warn(String.format("failed to remove host[uuid:%s] from local primary storage[uuid:%s], %s",
                                inventory.getUuid(), priUuid, reply.getError()));
                    } else {
                        logger.debug(String.format("removed host[uuid:%s] from local primary storage[uuid:%s]",
                                inventory.getUuid(), priUuid));
                    }
                }
            });
        }
    }

    @Override
    public void preAttachVolume(VmInstanceInventory vm, final VolumeInventory volume) {
        SimpleQuery<LocalStorageResourceRefVO> q = dbf.createQuery(LocalStorageResourceRefVO.class);
        q.select(LocalStorageResourceRefVO_.hostUuid);
        q.add(LocalStorageResourceRefVO_.resourceUuid, Op.IN, list(vm.getRootVolumeUuid(), volume.getUuid()));
        List<String> huuids = q.listValue();

        if (huuids.size() < 2) {
            return;
        }

        String rootHost = huuids.get(0);
        String dataHost = huuids.get(1);

        if (!rootHost.equals(dataHost)) {
            throw new OperationFailureException(errf.stringToOperationError(
                    String.format("cannot attach the data volume[uuid:%s] to the vm[uuid:%s]. Both vm's root volume and the data volume are" +
                            " on local primary storage, but they are on different hosts. The root volume[uuid:%s] is on the host[uuid:%s] but the data volume[uuid: %s]" +
                            " is on the host[uuid: %s]", volume.getUuid(), vm.getUuid(), vm.getRootVolumeUuid(), rootHost, volume.getUuid(), dataHost)
            ));
        }
    }

    @Override
    public void beforeAttachVolume(VmInstanceInventory vm, VolumeInventory volume) {

    }

    @Override
    public void afterAttachVolume(VmInstanceInventory vm, VolumeInventory volume) {

    }

    @Override
    public void failedToAttachVolume(VmInstanceInventory vm, VolumeInventory volume, ErrorCode errorCode) {

    }

    @Override
    @Transactional(readOnly = true)
    public List<VolumeVO> returnAttachableVolumes(VmInstanceInventory vm, List<VolumeVO> candidates) {
        // find instantiated volumes
        List<String> volUuids = CollectionUtils.transformToList(candidates, new Function<String, VolumeVO>() {
            @Override
            public String call(VolumeVO arg) {
                return VolumeStatus.Ready == arg.getStatus() ? arg.getUuid() : null;
            }
        });

        if (volUuids.isEmpty()) {
            return candidates;
        }

        List<VolumeVO> uninstantiatedVolumes = CollectionUtils.transformToList(candidates, new Function<VolumeVO, VolumeVO>() {
            @Override
            public VolumeVO call(VolumeVO arg) {
                return arg.getStatus() == VolumeStatus.NotInstantiated ? arg : null;
            }
        });

        String sql = "select ref.hostUuid from LocalStorageResourceRefVO ref where ref.resourceUuid = :volUuid and ref.resourceType = :rtype";
        TypedQuery<String>  q = dbf.getEntityManager().createQuery(sql, String.class);
        q.setParameter("volUuid", vm.getRootVolumeUuid());
        q.setParameter("rtype", VolumeVO.class.getSimpleName());
        List<String> ret = q.getResultList();
        if (ret.isEmpty()) {
            return candidates;
        }

        String hostUuid = ret.get(0);
        sql = "select ref.resourceUuid from LocalStorageResourceRefVO ref where ref.resourceUuid in (:uuids) and ref.resourceType = :rtype" +
                " and ref.hostUuid != :huuid";
        q = dbf.getEntityManager().createQuery(sql, String.class);
        q.setParameter("uuids", volUuids);
        q.setParameter("huuid", hostUuid);
        q.setParameter("rtype", VolumeVO.class.getSimpleName());
        final List<String> toExclude = q.getResultList();

        candidates = CollectionUtils.transformToList(candidates, new Function<VolumeVO, VolumeVO>() {
            @Override
            public VolumeVO call(VolumeVO arg) {
                return toExclude.contains(arg.getUuid()) ? null : arg;
            }
        });

        candidates.addAll(uninstantiatedVolumes);

        return candidates;
    }

    @Override
    @Transactional(readOnly = true)
    public HostMaintenancePolicy getHostMaintenancePolicy(HostInventory host) {
        String sql = "select count(ps) from PrimaryStorageVO ps, PrimaryStorageClusterRefVO ref where ps.uuid = ref.primaryStorageUuid" +
                " and ps.type = :type and ref.clusterUuid = :cuuid";
        TypedQuery<Long> q = dbf.getEntityManager().createQuery(sql, Long.class);
        q.setParameter("type", LocalStorageConstants.LOCAL_STORAGE_TYPE);
        q.setParameter("cuuid", host.getClusterUuid());
        q.setMaxResults(1);
        Long count = q.getSingleResult();
        return count > 0 ? HostMaintenancePolicy.StopVm : null;
    }

    @Override
    public void preDeleteVolume(VolumeInventory volume) {
    }

    @Override
    public void beforeDeleteVolume(VolumeInventory volume) {

    }

    @Override
    public void afterDeleteVolume(VolumeInventory volume) {
        if (volume.getPrimaryStorageUuid() != null && VolumeStatus.Deleted.toString().equals(volume.getStatus())) {
            SimpleQuery<LocalStorageResourceRefVO> q = dbf.createQuery(LocalStorageResourceRefVO.class);
            q.select(LocalStorageResourceRefVO_.hostUuid);
            q.add(LocalStorageResourceRefVO_.resourceUuid, Op.EQ, volume.getUuid());
            q.add(LocalStorageResourceRefVO_.resourceType, Op.EQ, VolumeVO.class.getSimpleName());
            String huuid = q.findValue();

            if (huuid == null) {
                return;
            }

            LocalStorageReturnHostCapacityMsg msg = new LocalStorageReturnHostCapacityMsg();
            msg.setHostUuid(huuid);
            msg.setPrimaryStorageUuid(volume.getPrimaryStorageUuid());
            msg.setSize(volume.getSize());
            bus.makeTargetServiceIdByResourceUuid(msg, PrimaryStorageConstant.SERVICE_ID, volume.getPrimaryStorageUuid());
            bus.send(msg);
        }
    }

    @Override
    public void failedToDeleteVolume(VolumeInventory volume, ErrorCode errorCode) {

    }

    @Override
    public List<ExpandedQueryStruct> getExpandedQueryStructs() {
        List<ExpandedQueryStruct> structs = new ArrayList<ExpandedQueryStruct>();

        ExpandedQueryStruct s = new ExpandedQueryStruct();
        s.setExpandedField("localStorageHostRef");
        s.setExpandedInventoryKey("resourceUuid");
        s.setForeignKey("uuid");
        s.setInventoryClass(LocalStorageResourceRefInventory.class);
        s.setInventoryClassToExpand(VolumeInventory.class);
        structs.add(s);

        s = new ExpandedQueryStruct();
        s.setExpandedField("localStorageHostRef");
        s.setExpandedInventoryKey("resourceUuid");
        s.setForeignKey("uuid");
        s.setInventoryClass(LocalStorageResourceRefInventory.class);
        s.setInventoryClassToExpand(VolumeSnapshotInventory.class);
        structs.add(s);

        return structs;
    }

    @Override
    public List<ExpandedQueryAliasStruct> getExpandedQueryAliasesStructs() {
        return null;
    }

    @Override
    @Transactional(readOnly = true)
    public List<VmInstanceVO> returnAttachableVms(VolumeInventory vol, List<VmInstanceVO> candidates) {
        String sql = "select ref.hostUuid from LocalStorageResourceRefVO ref where ref.resourceUuid = :uuid" +
                " and ref.resourceType = :rtype";
        TypedQuery<String> q = dbf.getEntityManager().createQuery(sql, String.class);
        q.setParameter("uuid", vol.getUuid());
        q.setParameter("rtype", VolumeVO.class.getSimpleName());
        List<String> ret = q.getResultList();
        if (ret.isEmpty()) {
            return candidates;
        }

        String hostUuid = ret.get(0);

        List<String> vmRootVolumeUuids = CollectionUtils.transformToList(candidates, new Function<String, VmInstanceVO>() {
            @Override
            public String call(VmInstanceVO arg) {
                return arg.getRootVolumeUuid();
            }
        });

        sql = "select ref.resourceUuid from LocalStorageResourceRefVO ref where ref.hostUuid = :huuid" +
                " and ref.resourceUuid in (:rootVolumeUuids) and ref.resourceType = :rtype";
        q = dbf.getEntityManager().createQuery(sql, String.class);
        q.setParameter("huuid", hostUuid);
        q.setParameter("rootVolumeUuids", vmRootVolumeUuids);
        q.setParameter("rtype", VolumeVO.class.getSimpleName());
        final List<String> toInclude = q.getResultList();

        candidates = CollectionUtils.transformToList(candidates, new Function<VmInstanceVO, VmInstanceVO>() {
            @Override
            public VmInstanceVO call(VmInstanceVO arg) {
                return toInclude.contains(arg.getRootVolumeUuid()) ? arg : null;
            }
        });

        return candidates;
    }

    @Override
    public void preRecoverDataVolume(VolumeInventory vol) {
        if (vol.getPrimaryStorageUuid() == null) {
            return;
        }

        SimpleQuery<PrimaryStorageVO> q = dbf.createQuery(PrimaryStorageVO.class);
        q.select(PrimaryStorageVO_.type);
        q.add(PrimaryStorageVO_.uuid, Op.EQ, vol.getPrimaryStorageUuid());
        String type = q.findValue();
        if (!LocalStorageConstants.LOCAL_STORAGE_TYPE.equals(type)) {
            return;
        }

        SimpleQuery<LocalStorageResourceRefVO> rq = dbf.createQuery(LocalStorageResourceRefVO.class);
        rq.add(LocalStorageResourceRefVO_.resourceUuid, Op.EQ, vol.getUuid());
        rq.add(LocalStorageResourceRefVO_.resourceType, Op.EQ, VolumeVO.class.getSimpleName());
        if (!rq.isExists()) {
            throw new OperationFailureException(errf.stringToOperationError(
                    String.format("the data volume[name:%s, uuid:%s] is on the local storage[uuid:%s]; however," +
                            "the host on which the data volume is has been deleted. Unable to recover this volume",
                            vol.getName(), vol.getUuid(), vol.getPrimaryStorageUuid())
            ));
        }
    }

    @Override
    public void beforeRecoverDataVolume(VolumeInventory vol) {

    }

    @Override
    public void afterRecoverDataVolume(VolumeInventory vol) {

    }

    @Override
    @Transactional(readOnly = true)
    public void preRecoverVm(VmInstanceInventory vm) {
        String rootVolUuid = vm.getRootVolumeUuid();

        String sql = "select ps.uuid from PrimaryStorageVO ps, VolumeVO vol where ps.uuid = vol.primaryStorageUuid" +
                " and vol.uuid = :uuid and ps.type = :pstype";
        TypedQuery<String> q = dbf.getEntityManager().createQuery(sql, String.class);
        q.setParameter("uuid", rootVolUuid);
        q.setParameter("pstype", LocalStorageConstants.LOCAL_STORAGE_TYPE);
        String psuuid = q.getSingleResult();
        if (psuuid == null) {
            return;
        }

        sql = "select count(ref) from LocalStorageResourceRefVO ref where ref.resourceUuid = :uuid and ref.resourceType = :rtype";
        TypedQuery<Long> rq = dbf.getEntityManager().createQuery(sql, Long.class);
        rq.setParameter("uuid", rootVolUuid);
        rq.setParameter("rtype", VolumeVO.class.getSimpleName());
        long count = rq.getSingleResult();
        if (count == 0) {
            throw new OperationFailureException(errf.stringToOperationError(
                    String.format("unable to recover the vm[uuid:%s, name:%s]. The vm's root volume is on the local" +
                            " storage[uuid:%s]; however, the host on which the root volume is has been deleted",
                            vm.getUuid(), vm.getName(), psuuid)
            ));
        }
    }

    @Override
    public void beforeRecoverVm(VmInstanceInventory vm) {

    }

    @Override
    public void afterRecoverVm(VmInstanceInventory vm) {

    }
}
