package org.zstack.test.integration.kvm.vm

import org.springframework.http.HttpEntity
import org.zstack.compute.vm.IsoOperator
import org.zstack.header.image.ImageConstant
import org.zstack.kvm.KVMAgentCommands
import org.zstack.kvm.KVMConstant
import org.zstack.sdk.DiskOfferingInventory
import org.zstack.sdk.ImageInventory
import org.zstack.sdk.InstanceOfferingInventory
import org.zstack.sdk.L3NetworkInventory
import org.zstack.sdk.VmInstanceInventory
import org.zstack.test.integration.kvm.KvmTest
import org.zstack.testlib.EnvSpec
import org.zstack.testlib.SubCase
import org.zstack.utils.data.SizeUnit

/**
 * Created by lining on 2018/02/10.
 */
class IsoBasicCase extends SubCase {
    EnvSpec env

    @Override
    void clean() {
        env.delete()
    }

    @Override
    void setup() {
        useSpring(KvmTest.springSpec)
    }

    @Override
    void environment() {
        env = env {
            instanceOffering {
                name = "instanceOffering"
                memory = SizeUnit.GIGABYTE.toByte(8)
                cpu = 4
            }

            diskOffering {
                name = "diskOffering"
                diskSize = SizeUnit.GIGABYTE.toByte(20)
            }

            sftpBackupStorage {
                name = "sftp"
                url = "/sftp"
                username = "root"
                password = "password"
                hostname = "localhost"

                image {
                    name = "iso_0"
                    url  = "http://zstack.org/download/test.iso"
                    format = ImageConstant.ISO_FORMAT_STRING.toString()
                }

                image {
                    name = "iso_1"
                    url  = "http://zstack.org/download/test.iso"
                    format = ImageConstant.ISO_FORMAT_STRING.toString()
                }

                image {
                    name = "iso_2"
                    url  = "http://zstack.org/download/test.iso"
                    format = ImageConstant.ISO_FORMAT_STRING.toString()
                }

                image {
                    name = "iso_3"
                    url  = "http://zstack.org/download/test.iso"
                    format = ImageConstant.ISO_FORMAT_STRING.toString()
                }

                image {
                    name = "image"
                    url  = "http://zstack.org/download/image.qcow2"
                }
            }

            zone {
                name = "zone"
                description = "test"

                cluster {
                    name = "cluster"
                    hypervisorType = "KVM"

                    kvm {
                        name = "kvm"
                        managementIp = "localhost"
                        username = "root"
                        password = "password"
                    }

                    attachPrimaryStorage("local")
                    attachL2Network("l2")
                }

                localPrimaryStorage {
                    name = "local"
                    url = "/local_ps"
                }

                l2NoVlanNetwork {
                    name = "l2"
                    physicalInterface = "eth0"

                    l3Network {
                        name = "l3"

                        ip {
                            startIp = "12.16.10.10"
                            endIp = "12.16.10.100"
                            netmask = "255.255.255.0"
                            gateway = "12.16.10.1"
                        }
                    }
                }
                attachBackupStorage("sftp")
            }

            vm {
                name = "vm"
                useL3Networks("l3")
                useInstanceOffering("instanceOffering")
                useRootDiskOffering("diskOffering")
                useImage("iso_1")
            }
        }

    }

    @Override
    void test() {
        env.create {
            testAttach3Iso()
        }
    }

    void testAttach3Iso() {
        ImageInventory iso0 = env.inventoryByName("iso_0")
        ImageInventory iso1 = env.inventoryByName("iso_1")
        ImageInventory iso2 = env.inventoryByName("iso_2")
        ImageInventory iso3 = env.inventoryByName("iso_3")
        DiskOfferingInventory diskOffering = env.inventoryByName("diskOffering")
        InstanceOfferingInventory instanceOffering = env.inventoryByName("instanceOffering")
        L3NetworkInventory l3 = env.inventoryByName("l3")

        KVMAgentCommands.StartVmCmd cmd
        env.afterSimulator(KVMConstant.KVM_START_VM_PATH) { rsp, HttpEntity<String> e ->
            cmd = json(e.body, KVMAgentCommands.StartVmCmd.class)
            assert 1 == cmd.bootIso.size()
            KVMAgentCommands.IsoTO isoTO = cmd.bootIso.get(0)
            assert iso0.uuid == isoTO.imageUuid
            assert 0 == isoTO.deviceId
            assert null != isoTO.path

            return rsp
        }

        VmInstanceInventory newVm = createVmInstance {
            name = "new-vm"
            instanceOfferingUuid = instanceOffering.uuid
            imageUuid = iso0.uuid
            l3NetworkUuids = [l3.uuid]
            rootDiskOfferingUuid = diskOffering.uuid
        }
        assert null != cmd

        checkIsoSystemTag(newVm.uuid, iso0.uuid, 0)
        checkVmIsoNum(newVm.uuid, 1)

        attachIso(newVm.uuid, iso1.uuid, 1)
        checkVmIsoNum(newVm.uuid, 2)

        attachIso(newVm.uuid, iso2.uuid, 2)
        checkVmIsoNum(newVm.uuid, 3)

        expect(AssertionError.class) {
            attachIso(newVm.uuid, iso3.uuid, 3)
        }
        checkVmIsoNum(newVm.uuid, 3)

        detachIso(newVm.uuid, iso1.uuid, 1)
        checkVmIsoNum(newVm.uuid, 2)

        Map<String, KVMAgentCommands.IsoTO> expectIsoMap = new HashMap<>()
        KVMAgentCommands.IsoTO isoTO = new KVMAgentCommands.IsoTO()
        isoTO.imageUuid = iso0.uuid
        isoTO.setDeviceId(0)
        expectIsoMap.put(isoTO.imageUuid, isoTO)
        isoTO = new KVMAgentCommands.IsoTO()
        isoTO.imageUuid = iso2.uuid
        isoTO.setDeviceId(2)
        expectIsoMap.put(isoTO.imageUuid, isoTO)
        rebootVm(newVm.uuid, expectIsoMap)
        checkVmIsoNum(newVm.uuid, 2)

        attachIso(newVm.uuid, iso3.uuid, 1)
        checkVmIsoNum(newVm.uuid, 3)

        detachIso(newVm.uuid, iso0.uuid, 0)
        detachIso(newVm.uuid, iso3.uuid, 1)
        detachIso(newVm.uuid, iso2.uuid, 2)
        checkVmIsoNum(newVm.uuid, 0)

        rebootVm(newVm.uuid, new HashMap<String, KVMAgentCommands.IsoTO>())
        checkVmIsoNum(newVm.uuid, 0)

        attachIso(newVm.uuid, iso3.uuid, 0)
        checkVmIsoNum(newVm.uuid, 1)

        destroyVmInstance {
            uuid = newVm.uuid
        }
        deleteImage {
            uuid = iso3.uuid
        }
    }

    void attachIso(String vmUuid, String iso, int expectIsoDeviceId) {
        KVMAgentCommands.AttachIsoCmd cmd
        env.afterSimulator(KVMConstant.KVM_ATTACH_ISO_PATH) { rsp, HttpEntity<String> e ->
            cmd = json(e.body, KVMAgentCommands.AttachIsoCmd.class)
            assert expectIsoDeviceId == cmd.iso.deviceId
            assert iso == cmd.iso.imageUuid
            assert null != cmd.iso.path

            return rsp
        }

        attachIsoToVmInstance {
            vmInstanceUuid = vmUuid
            isoUuid = iso
        }

        assert null != cmd
        checkIsoSystemTag(vmUuid, iso, expectIsoDeviceId)

    }

    void detachIso(String vmUuid, String iso, int expectIsoDeviceId) {

        KVMAgentCommands.DetachIsoCmd cmd
        env.afterSimulator(KVMConstant.KVM_DETACH_ISO_PATH) { rsp, HttpEntity<String> e ->
            cmd = json(e.body, KVMAgentCommands.DetachIsoCmd.class)
            assert iso == cmd.isoUuid
            assert expectIsoDeviceId == cmd.deviceId
            assert vmUuid == cmd.vmUuid

            return rsp
        }

        detachIsoFromVmInstance {
            vmInstanceUuid = vmUuid
            isoUuid = iso
        }
        assert null != cmd
        assert !IsoOperator.getIsoUuidByVmUuid(vmUuid).contains(iso)
    }

    void rebootVm(String vmUuid, Map<String, KVMAgentCommands.IsoTO> expectIsoMap) {
        KVMAgentCommands.StartVmCmd cmd
        env.afterSimulator(KVMConstant.KVM_START_VM_PATH) { rsp, HttpEntity<String> e ->
            cmd = json(e.body, KVMAgentCommands.StartVmCmd.class)
            assert expectIsoMap.keySet().size() == cmd.bootIso.size()

            for (KVMAgentCommands.IsoTO isoTO : cmd.bootIso) {
                KVMAgentCommands.IsoTO expect = expectIsoMap.get(isoTO.getImageUuid())
                assert null != expect
                assert expect.deviceId == isoTO.deviceId
                assert null != isoTO.path
            }

            return rsp
        }

        rebootVmInstance {
            uuid = vmUuid
        }
        assert null != cmd
    }

    void checkIsoSystemTag(String vmUuid, String isoUuid, int expectIsoDeviceId) {
        assert expectIsoDeviceId == IsoOperator.getIsoDeviceId(vmUuid, isoUuid)
    }

    void checkVmIsoNum(String vmUuid, int expectIsoNum) {
        assert expectIsoNum == IsoOperator.getIsoUuidByVmUuid(vmUuid).size()
    }

}